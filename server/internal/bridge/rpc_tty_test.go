package bridge

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"golang.org/x/term"
)

func TestProcessStampDistinguishesReapedOwner(t *testing.T) {
	stamp, err := processStamp(os.Getpid())
	if err != nil || stamp == "" {
		t.Fatalf("current owner identity: %q, %v", stamp, err)
	}
	child := exec.Command("/bin/sh", "-c", "exit 0")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	if err := child.Wait(); err != nil {
		t.Fatal(err)
	}
	if _, err := processStamp(child.Process.Pid); !errors.Is(err, unix.ESRCH) && !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("reaped owner must be absent, not an inspection failure: %v", err)
	}
}

// The child owns a lease but deliberately cannot run defer cleanup after its
// parent sends SIGKILL. This is an actual separate process, not a mocked crash.
func TestRPCInputCrashHelper(t *testing.T) {
	if os.Getenv("ISSUE56_TTY_CRASH_HELPER") != "1" {
		t.Skip("helper process")
	}
	root := os.Getenv("ISSUE56_NATIVE_ROOT")
	ctx := context.Background()
	p := NewPane(filepath.Join(root, "tty-crash.sock"), "%0")
	process, err := p.NativePi(ctx)
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = p.RPCInput(ctx, process)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Println("READY")
	<-time.After(time.Hour)
}

func TestRPCTTYCrashReattachRestoresExactOriginalState(t *testing.T) {
	root := os.Getenv("ISSUE56_NATIVE_ROOT")
	if root == "" {
		t.Skip("set project-local ISSUE56_NATIVE_ROOT for official Pi")
	}
	if !filepath.IsAbs(root) {
		t.Fatal("native root must be absolute")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	sock := filepath.Join(root, "tty-crash.sock")
	if len(sock) >= 100 {
		t.Fatal("native socket path too long")
	}
	pi, err := exec.LookPath("pi")
	if err != nil {
		t.Fatal(err)
	}
	tmux := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-S", sock}, args...)...)
		cmd.Env = scrubbedEnv()
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("owned tmux %s: %v", args[0], err)
		}
		return strings.TrimSpace(string(out))
	}
	tmux("new-session", "-d", "-s", "issue56-tty-crash", "-c", root, pi, "--mode", "rpc", "--no-session", "--no-extensions", "--no-skills")
	t.Cleanup(func() { cmd := exec.Command("tmux", "-S", sock, "kill-server"); cmd.Env = scrubbedEnv(); cmd.Run() })
	if tmux("list-sessions", "-F", "#{socket_path} #{session_name}") != sock+" issue56-tty-crash" {
		t.Fatal("wrong socket identity")
	}
	p := NewPane(sock, "%0")
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	var process PiProcess
	for until := time.Now().Add(10 * time.Second); time.Now().Before(until); {
		process, err = p.NativePi(ctx)
		if err == nil && process.Mode == "rpc" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	fd, err := os.OpenFile(process.TTY, os.O_RDWR, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer fd.Close()
	initial, err := term.GetState(int(fd.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	defer term.Restore(int(fd.Fd()), initial) // only this owned native fixture
	for _, raw := range []bool{false, true} {
		t.Run(fmt.Sprintf("initial_raw_%v", raw), func(t *testing.T) {
			if raw {
				if _, err := term.MakeRaw(int(fd.Fd())); err != nil {
					t.Fatal(err)
				}
			} else if err := term.Restore(int(fd.Fd()), initial); err != nil {
				t.Fatal(err)
			}
			original, err := term.GetState(int(fd.Fd()))
			if err != nil {
				t.Fatal(err)
			}
			exe, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			child := exec.CommandContext(ctx, exe, "-test.run=^TestRPCInputCrashHelper$")
			child.Env = append(scrubbedEnv(), "ISSUE56_TTY_CRASH_HELPER=1", "ISSUE56_NATIVE_ROOT="+root)
			stdout, err := child.StdoutPipe()
			if err != nil {
				t.Fatal(err)
			}
			if err := child.Start(); err != nil {
				t.Fatal(err)
			}
			reaped := false
			defer func() {
				if !reaped {
					child.Process.Kill()
					child.Wait()
				}
			}()
			line, err := bufio.NewReader(stdout).ReadString('\n')
			if err != nil || strings.TrimSpace(line) != "READY" {
				t.Fatal("crash helper did not acquire tty lease")
			}
			if err := child.Process.Kill(); err != nil {
				t.Fatal(err)
			}
			if err := child.Wait(); err == nil {
				t.Fatal("helper was not killed")
			}
			reaped = true
			_, restore, err := p.RPCInput(ctx, process)
			if err != nil {
				t.Fatal(err)
			}
			restore()
			after, err := term.GetState(int(fd.Fd()))
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(original, after) {
				t.Fatal("reattach/graceful cleanup did not restore exact pre-crash termios")
			}
			if tmux("display-message", "-p", "-t", "%0", "#{"+rpcTTYOption+"}") != "" {
				t.Fatal("graceful cleanup retained tty lease")
			}
			// A canceled attachment can be immediately replaced before its old
			// cleanup runs. That cleanup must not restore the new owner's tty.
			oldCtx, cancelOld := context.WithCancel(ctx)
			_, oldRestore, err := p.RPCInput(oldCtx, process)
			if err != nil {
				t.Fatal(err)
			}
			defer oldRestore()
			defer cancelOld()
			if _, unexpected, err := p.RPCInput(ctx, process); err == nil {
				unexpected()
				t.Fatal("live tty owner accepted a duplicate lease")
			}
			cancelOld()
			_, newRestore, err := p.RPCInput(ctx, process)
			if err != nil {
				t.Fatal(err)
			}
			defer newRestore()
			owned, err := term.GetState(int(fd.Fd()))
			if err != nil {
				t.Fatal(err)
			}
			oldRestore()
			afterOld, err := term.GetState(int(fd.Fd()))
			if err != nil || !reflect.DeepEqual(owned, afterOld) {
				t.Fatal("old cleanup overwrote the replacement lease")
			}
			newRestore()
			afterNew, err := term.GetState(int(fd.Fd()))
			if err != nil || !reflect.DeepEqual(original, afterNew) {
				t.Fatal("replacement lease lost original tty provenance")
			}
		})
	}
}
