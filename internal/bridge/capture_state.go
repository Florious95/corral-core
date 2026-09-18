package bridge

import (
	"bytes"
	"context"
	"fmt"
	"strconv"
	"strings"
)

// CapturedPane keeps the screen, cursor, mouse modes and native wrap flags from
// the same tmux command group, rather than separate client round trips while
// the application may be rendering another frame.
type CapturedPane struct {
	Data        []byte
	Cols, Rows  int
	CursorX     int
	CursorY     int
	Mouse       MouseMode
	WrappedRows bool
}

func (p *Pane) CaptureState(ctx context.Context) (CapturedPane, error) {
	out, err := runTmux(ctx, p.socket, p.timeout,
		"display-message", "-p", "-t", p.target,
		"#{pane_width}|#{pane_height}|#{cursor_x}|#{cursor_y}|#{mouse_any_flag}|#{mouse_standard_flag}|#{mouse_button_flag}|#{mouse_all_flag}|#{mouse_sgr_flag}",
		";", "capture-pane", "-e", "-p", "-t", p.target,
		";", "capture-pane", "-J", "-p", "-t", p.target)
	if err != nil {
		return CapturedPane{}, err
	}
	return parseCapturedPane(out)
}

func parseCapturedPane(out []byte) (CapturedPane, error) {
	header, rest, ok := bytes.Cut(out, []byte{'\n'})
	if !ok {
		return CapturedPane{}, fmt.Errorf("tmux: missing capture metadata")
	}
	fields := strings.Split(string(header), "|")
	if len(fields) != 9 {
		return CapturedPane{}, fmt.Errorf("tmux: capture metadata needs nine fields")
	}
	var values [9]int
	for i, field := range fields {
		value, err := strconv.Atoi(field)
		if err != nil || value < 0 || i >= 4 && value > 1 {
			return CapturedPane{}, fmt.Errorf("tmux: invalid capture field %d: %q", i, field)
		}
		values[i] = value
	}
	// tmux represents pending autowrap with cursor_x == pane_width.
	if values[0] == 0 || values[1] == 0 || values[2] > values[0] || values[3] >= values[1] {
		return CapturedPane{}, fmt.Errorf("tmux: invalid capture geometry/cursor")
	}
	// capture-pane prints exactly one LF per physical row, including blanks.
	// The following -J capture joins native soft-wrapped rows. Its LF count
	// exposes wrap state without guessing Unicode cell widths or parsing ANSI.
	end := 0
	for row := 0; row < values[1]; row++ {
		next := bytes.IndexByte(rest[end:], '\n')
		if next < 0 {
			return CapturedPane{}, fmt.Errorf("tmux: incomplete physical capture")
		}
		end += next + 1
	}
	joinedRows := bytes.Count(rest[end:], []byte{'\n'})
	if joinedRows == 0 || joinedRows > values[1] {
		return CapturedPane{}, fmt.Errorf("tmux: invalid joined capture row count %d", joinedRows)
	}
	return CapturedPane{
		Data: rest[:end], Cols: values[0], Rows: values[1],
		CursorX: values[2], CursorY: values[3],
		Mouse: MouseMode{
			Any: values[4] == 1, Standard: values[5] == 1,
			Button: values[6] == 1, All: values[7] == 1, SGR: values[8] == 1,
		},
		WrappedRows: joinedRows < values[1],
	}, nil
}
