use nodeprobe::{probe, SocketSpec};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

fn exe(path: &Path, body: &str) {
    fs::write(path, body).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

/// Under tmux Claude Code keeps a static `✳` title whether busy or idle; the
/// session registry, not the title, must decide activity.
#[test]
fn claude_activity_follows_session_registry_not_static_title() {
    let root = std::env::temp_dir().join(format!("nodeprobe-claude-e2e-{}", std::process::id()));
    let _ = fs::remove_dir_all(&root);
    let sessions = root.join("sessions");
    fs::create_dir_all(&sessions).unwrap();
    exe(
        &root.join("tmux"),
        "#!/bin/sh\nprintf 'team\\0371\\037seat\\037%%102\\0374242\\037bash\\037✳ seat\\037/tmp/project\\n'\n",
    );
    exe(
        &root.join("ps"),
        "#!/bin/sh\nprintf '4242 1 Ss+ bash\\n4243 4242 S+ /Users/x/.local/share/claude/versions/2.1.309\\n'\n",
    );
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/providers.tsv");
    let old_path = std::env::var("PATH").unwrap_or_default();
    std::env::set_var("PATH", format!("{}:{old_path}", root.display()));
    std::env::set_var("NODEPROBE_PROVIDERS", &fixtures);
    std::env::set_var("NODEPROBE_CLAUDE_SESSIONS_DIR", &sessions);

    let sample = || {
        let report = probe(SocketSpec::Path("/tmp/fake.sock".into())).unwrap();
        assert_eq!(report.nodes.len(), 1);
        report.nodes[0].clone()
    };
    let record = |status: &str, pane: &str| {
        fs::write(
            sessions.join("4243.json"),
            format!(
                r#"{{"pid":4243,"status":"{status}","tmux":"team:@102.{pane}","name":"seat"}}"#
            ),
        )
        .unwrap();
    };

    record("busy", "%102");
    let node = sample();
    assert_eq!(node.provider, "claude_code");
    assert_eq!(
        (node.activity.as_str(), node.state.as_str()),
        ("working", "working")
    );
    assert_eq!(node.evidence.method, "claude_session_registry");
    assert!(
        node.evidence.detail.contains("pid=4243 status=busy"),
        "{}",
        node.evidence.detail
    );

    record("waiting", "%102");
    assert_eq!(sample().activity, "idle");
    record("idle", "%102");
    assert_eq!(sample().activity, "idle");

    // A registry left by a different pane (PID reuse) is not adopted: the
    // title fallback stays visible in evidence.
    record("busy", "%7");
    let node = sample();
    assert_eq!(node.activity, "idle");
    assert_eq!(node.evidence.method, "pane_title");
    assert!(
        node.evidence
            .detail
            .contains("claude_session_registry unavailable"),
        "{}",
        node.evidence.detail
    );

    std::env::set_var("PATH", old_path);
    let _ = fs::remove_dir_all(root);
}
