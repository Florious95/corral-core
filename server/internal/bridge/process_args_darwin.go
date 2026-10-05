//go:build darwin

package bridge

import (
	"bytes"
	"encoding/binary"
	"errors"

	"golang.org/x/sys/unix"
)

func setRPCInput(fd int) error {
	settings, err := unix.IoctlGetTermios(fd, unix.TIOCGETA)
	if err != nil {
		return err
	}
	settings.Lflag &^= unix.ICANON | unix.ECHO
	settings.Cc[unix.VMIN], settings.Cc[unix.VTIME] = 1, 0
	return unix.IoctlSetTermios(fd, unix.TIOCSETA, settings)
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
