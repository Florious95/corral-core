//go:build linux

package bridge

import (
	"bytes"
	"fmt"
	"golang.org/x/sys/unix"
	"os"
)

func setRPCInput(fd int) error {
	settings, err := unix.IoctlGetTermios(fd, unix.TCGETS)
	if err != nil {
		return err
	}
	settings.Lflag &^= unix.ICANON | unix.ECHO
	settings.Cc[unix.VMIN], settings.Cc[unix.VTIME] = 1, 0
	return unix.IoctlSetTermios(fd, unix.TCSETS, settings)
}

// Read only the selected foreground process's argv, never its environment.
func processArguments(pid int) ([]string, error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil {
		return nil, err
	}
	parts := bytes.Split(bytes.TrimSuffix(data, []byte{0}), []byte{0})
	args := make([]string, len(parts))
	for i, p := range parts {
		args[i] = string(p)
	}
	return args, nil
}
