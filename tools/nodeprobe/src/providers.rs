//! Shared whitelist: tools/nodeprobe/fixtures/providers.tsv (same file Go reads).

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub comm: String,
    pub id: String,
    pub display: String,
}

static TABLE: OnceLock<Vec<Entry>> = OnceLock::new();

pub fn default_providers_tsv() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/providers.tsv")
}

pub fn load() -> &'static [Entry] {
    TABLE.get_or_init(|| load_path(&default_providers_tsv()))
}

fn load_path(path: &Path) -> Vec<Entry> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = line.split('\t');
        let comm = parts.next().unwrap_or("").to_string();
        let id = parts.next().unwrap_or("").to_string();
        let display = parts.next().unwrap_or("").to_string();
        if comm.is_empty() || id.is_empty() {
            continue;
        }
        out.push(Entry { comm, id, display });
    }
    out
}

pub fn basename(comm: &str) -> &str {
    Path::new(comm)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or(comm)
}

pub fn lookup(comm: &str) -> Option<&'static Entry> {
    let base = basename(comm);
    load().iter().find(|e| e.comm == base)
}

pub fn match_comms(comms: &[String]) -> Option<&'static Entry> {
    comms.iter().find_map(|c| lookup(c))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn five_rows_and_basename() {
        let t = load();
        assert_eq!(t.len(), 5);
        assert_eq!(lookup("codex").map(|e| e.id.as_str()), Some("codex"));
        let full = "/opt/homebrew/bin/codex";
        assert_ne!(full, "codex");
        assert_eq!(lookup(full).map(|e| e.id.as_str()), Some("codex"));
        assert!(lookup("bash").is_none());
    }

    /// Same comm→id table as server/internal/provider/table_test.go, same TSV.
    #[test]
    fn provider_corpus_parity_with_go() {
        let want = [
            ("claude", "claude_code"),
            ("codex", "codex"),
            ("copilot", "copilot"),
            ("grok", "grok"),
            ("cursor-agent", "cursor"),
        ];
        for (comm, id) in want {
            let e = lookup(comm).unwrap_or_else(|| panic!("comm {comm} missing"));
            assert_eq!(e.id, id, "comm {comm}");
        }
        let full = "/opt/homebrew/Cellar/node/24.1.0/bin/codex";
        assert_ne!(full, "codex");
        assert!(
            load().iter().all(|e| e.comm != full),
            "tsv must store basename, not full path"
        );
        assert_eq!(lookup(full).map(|e| e.id.as_str()), Some("codex"));
        for noise in ["bash", "sleep", "vim", "make", "sshd", ""] {
            assert!(lookup(noise).is_none(), "noise {noise} must not be a node");
        }
    }
}
