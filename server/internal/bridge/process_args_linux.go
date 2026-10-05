//go:build linux

package bridge

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"strings"

	"golang.org/x/sys/unix"
)

func ttySettings(fd int) (*unix.Termios, error) { return unix.IoctlGetTermios(fd, unix.TCGETS) }
func setTTYSettings(fd int, settings *unix.Termios) error {
	return unix.IoctlSetTermios(fd, unix.TCSETS, settings)
}

func processStamp(pid int) (string, error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return "", err
	}
	at := strings.LastIndexByte(string(data), ')')
	if at < 0 {
		return "", errors.New("invalid process identity")
	}
	fields := strings.Fields(string(data[at+1:]))
	if len(fields) < 20 {
		return "", errors.New("incomplete process identity")
	}
	return fields[19], nil
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
