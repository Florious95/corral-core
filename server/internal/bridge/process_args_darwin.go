//go:build darwin

package bridge

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"

	"golang.org/x/sys/unix"
)

func ttySettings(fd int) (*unix.Termios, error) { return unix.IoctlGetTermios(fd, unix.TIOCGETA) }
func setTTYSettings(fd int, settings *unix.Termios) error {
	return unix.IoctlSetTermios(fd, unix.TIOCSETA, settings)
}

func processStamp(pid int) (string, error) {
	// The singular helper reports EIO for an exited PID's empty result. The
	// slice API distinguishes absence from an actual inspection failure.
	info, err := unix.SysctlKinfoProcSlice("kern.proc.pid", pid)
	if err != nil {
		return "", err
	}
	if len(info) != 1 || int(info[0].Proc.P_pid) != pid {
		return "", unix.ESRCH
	}
	stamp := info[0].Proc.P_starttime
	return fmt.Sprintf("%d.%06d", stamp.Sec, stamp.Usec), nil
}

func setRPCInput(fd int) error {
	settings, err := ttySettings(fd)
	if err != nil {
		return err
	}
	settings.Lflag &^= unix.ICANON | unix.ECHO
	settings.Cc[unix.VMIN], settings.Cc[unix.VTIME] = 1, 0
	return setTTYSettings(fd, settings)
}

// Kernel argv preserves spaces and quoting without shell parsing or exposing
// unrelated process arguments. No argv or environment bytes leave this call.
func processArguments(pid int) ([]string, error) {
	data, err := unix.SysctlRaw("kern.procargs2", pid)
	if err != nil || len(data) < 5 {
		return nil, errors.New("process argv unavailable")
	}
	argc := int(binary.NativeEndian.Uint32(data[:4]))
	data = data[4:]
	at := bytes.IndexByte(data, 0)
	if at < 0 {
		return nil, errors.New("invalid process argv")
	}
	data = bytes.TrimLeft(data[at+1:], "\x00")
	args := make([]string, 0, argc)
	for i := 0; i < argc; i++ {
		at = bytes.IndexByte(data, 0)
		if at < 0 {
			return nil, errors.New("incomplete process argv")
		}
		args = append(args, string(data[:at]))
		data = data[at+1:]
	}
	return args, nil
}
