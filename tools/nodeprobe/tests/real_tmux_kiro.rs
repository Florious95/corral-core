//! Real tmux/provider-identity red test for Kiro CLI.
//!
//! This deliberately uses a private tmux server and real pane processes. The
//! panes run `/bin/sleep` with an argv[0]/process name of `kiro-cli` or
//! `kiro-cli-chat`; nodeprobe is only allowed to observe the resulting `comm`
//! field, exactly as it does for a real CLI. `node`, `python`, and `q` are
//! negative controls and must remain unknown.

#![cfg(target_os = "macos")]

use nodeprobe::{probe, web, Report, SocketSpec};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::process::{Command, Output};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

struct PrivateTmux {
    root: PathBuf,
    socket: PathBuf,
}

impl PrivateTmux {
    fn new() -> Self {
        assert!(PathBuf::from("/bin/zsh").is_file(), "macOS zsh is required");
        assert!(
            PathBuf::from("/bin/sleep").is_file(),
            "macOS sleep is required"
        );

        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock before epoch")
            .as_nanos();
        // tmux's Unix socket has a short path limit. /private/tmp is still a
        // local, user-private test runway and keeps the generated socket
        // below that platform limit.
        let root = PathBuf::from("/private/tmp").join(format!(
            "nodeprobe-real-tmux-{}-{nanos}",
            std::process::id()
        ));
        fs::create_dir(&root).expect("create private nodeprobe directory");
        fs::set_permissions(&root, fs::Permissions::from_mode(0o700))
            .expect("lock private nodeprobe directory");

        let runner = Self {
            socket: root.join("tmux.sock"),
            root,
        };
        for (session, process_name) in [
            ("kiro-cli", "kiro-cli"),
            ("kiro-cli-chat", "kiro-cli-chat"),
            ("node", "node"),
            ("python", "python"),
            ("q", "q"),
        ] {
            runner.start_pane(session, process_name);
        }
        runner.wait_until_ready();
        runner
    }

    fn tmux(&self) -> Command {
        let mut command = Command::new("tmux");
        command
            .arg("-S")
            .arg(&self.socket)
            .arg("-f")
            .arg("/dev/null");
        command
    }

    fn start_pane(&self, session: &str, process_name: &str) {
        // zsh's exec -a sets the real process comm/argv[0] while retaining a
        // long-lived executable. No pane body, command arguments, or input is
        // used by nodeprobe.
        let command_line = format!("exec /bin/zsh -f -c 'exec -a {process_name} /bin/sleep 90'");
        let output = self
            .tmux()
            .args(["new-session", "-d", "-s", session, "-n", "main", "-c"])
            .arg(&self.root)
            .arg(command_line)
            .output()
            .expect("start private tmux session");
        assert_success(output, "start private tmux session");
    }

    fn wait_until_ready(&self) {
        let spec = SocketSpec::Path(self.socket.to_string_lossy().into_owned());
        let mut last = String::new();
        for _ in 0..80 {
            match nodeprobe::list_panes(&spec) {
                Ok(panes)
                    if panes.len() == 5
                        && panes.iter().all(|pane| pane.current_command == "sleep") =>
                {
                    thread::sleep(Duration::from_millis(100));
                    return;
                }
                Ok(panes) => last = format!("{} panes: {panes:?}", panes.len()),
                Err(error) => last = error,
            }
            thread::sleep(Duration::from_millis(25));
        }
        panic!("private tmux panes did not become ready: {last}");
    }

    fn spec(&self) -> SocketSpec {
        SocketSpec::Path(self.socket.to_string_lossy().into_owned())
    }
}

impl Drop for PrivateTmux {
    fn drop(&mut self) {
        let _ = self.tmux().arg("kill-server").output();
        let _ = fs::remove_dir_all(&self.root);
    }
}

fn assert_success(output: Output, action: &str) {
    assert!(
        output.status.success(),
        "{action} failed: status={:?} stdout={} stderr={}",
        output.status,
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

fn node_for<'a>(report: &'a Report, session: &str) -> Option<&'a nodeprobe::Node> {
    report.nodes.iter().find(|node| node.session == session)
}

#[test]
fn real_tmux_kiro_cli_reaches_nodeprobe_status_listing() {
    let tmux = PrivateTmux::new();
    let spec = tmux.spec();
    let report = probe(spec.clone()).expect("real private tmux probe");
    let status = web::snapshot(std::slice::from_ref(&spec));

    let mut failures = Vec::new();
    for (session, expected_provider) in [
        ("kiro-cli", "kiro_cli"),
        ("kiro-cli-chat", "kiro_cli"),
        ("node", "unknown"),
        ("python", "unknown"),
        ("q", "unknown"),
    ] {
        match node_for(&report, session) {
            None => failures.push(format!("direct report missing session={session}")),
            Some(node) if node.provider != expected_provider => failures.push(format!(
                "direct session={session} provider={:?} want={expected_provider:?} comms={:?} title={:?}",
                node.provider, node.evidence.comms, node.evidence.title
            )),
            Some(_) => {}
        }
        match status.nodes.iter().find(|node| node.session == session) {
            None => failures.push(format!("status listing missing session={session}")),
            Some(node) if node.provider != expected_provider => failures.push(format!(
                "status session={session} provider={:?} want={expected_provider:?} comms={:?} title={:?}",
                node.provider, node.evidence.comms, node.evidence.title
            )),
            Some(_) => {}
        }
    }

    if report.error.is_some() {
        failures.push(format!("direct report error={:?}", report.error));
    }
    if !status.errors.is_empty() {
        failures.push(format!("status listing errors={:?}", status.errors));
    }
    if !failures.is_empty() {
        panic!(
            "real tmux Kiro red baseline failed:\n{}\ndirect report:\n{}\nstatus listing:\n{}",
            failures.join("\n"),
            serde_json::to_string_pretty(&report).expect("serialize direct report"),
            serde_json::to_string_pretty(&status).expect("serialize status listing")
        );
    }
}
