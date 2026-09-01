use nodeprobe::classify::BackgroundTasks;
use nodeprobe::{probe, SocketSpec, SCHEMA_VERSION};
use std::fs;
use std::os::unix::fs::PermissionsExt;

#[test]
fn fake_tmux_inventory_is_read_only_and_unknown_is_explicit() {
    let root = std::env::temp_dir().join(format!("nodeprobe-fake-{}", std::process::id()));
    let _ = fs::remove_dir_all(&root);
    fs::create_dir_all(&root).unwrap();
    let script = root.join("tmux");
    fs::write(
        &script,
        r##"#!/bin/sh
if [ "$1" = "-S" ] || [ "$1" = "-L" ]; then shift 2; fi
case "$1" in
  list-panes) printf 'sess\0372\037mystery\037%%7\0370\037※ unknown\n' ;;
  *) exit 2 ;;
esac
"##,
    )
    .unwrap();
    fs::set_permissions(&script, fs::Permissions::from_mode(0o755)).unwrap();
    let old_path = std::env::var_os("PATH");
    let old_providers = std::env::var_os("NODEPROBE_PROVIDERS");
    let old = std::env::var_os("PATH").unwrap_or_default();
    let path = std::ffi::OsString::from(root.to_string_lossy().to_string() + ":")
        .into_string()
        .unwrap();
    let path = format!("{path}{}", old.to_string_lossy());
    std::env::set_var("PATH", path);
    std::env::set_var("NODEPROBE_PROVIDERS", root.join("missing-providers.tsv"));

    let report = probe(SocketSpec::Path("/tmp/fake.sock".into())).unwrap();
    assert_eq!(report.schema_version, SCHEMA_VERSION);
    assert_eq!(report.nodes.len(), 1);
    let node = &report.nodes[0];
    assert_eq!(node.provider, "unknown");
    assert_eq!(node.state, "unknown");
    assert_eq!(node.evidence.codepoint.as_deref(), Some("U+203B"));
    assert_eq!(node.background_tasks, BackgroundTasks::Unknown);
    let error = report.error.as_ref().expect("corpus capability error");
    assert_eq!(error.kind, "provider_corpus_unavailable");
    assert!(error.message.contains("read"), "{}", error.message);

    match old_path {
        Some(v) => std::env::set_var("PATH", v),
        None => std::env::remove_var("PATH"),
    }
    match old_providers {
        Some(v) => std::env::set_var("NODEPROBE_PROVIDERS", v),
        None => std::env::remove_var("NODEPROBE_PROVIDERS"),
    }
    let _ = fs::remove_dir_all(root);
}
