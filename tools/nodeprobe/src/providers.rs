//! Shared whitelist: tools/nodeprobe/fixtures/providers.tsv (same file Go reads).

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub comm: String,
    pub id: String,
    pub display: String,
    pub path_segment: bool,
}

pub const CORPUS_UNAVAILABLE_KIND: &str = "provider_corpus_unavailable";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CorpusError {
    pub kind: &'static str,
    pub message: String,
}

static TABLE: OnceLock<Result<Vec<Entry>, CorpusError>> = OnceLock::new();

pub fn default_providers_tsv() -> PathBuf {
    std::env::var_os("NODEPROBE_PROVIDERS")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/providers.tsv"))
}

pub fn load() -> &'static [Entry] {
    match TABLE.get_or_init(|| load_path(&default_providers_tsv())) {
        Ok(entries) => entries,
        Err(_) => &[],
    }
}

/// Return the corpus load failure, if the configured provider corpus was not
/// available. `load()` remains a compatibility API but callers needing an
/// honest capability must inspect this status.
pub fn corpus_error() -> Option<&'static CorpusError> {
    match TABLE.get_or_init(|| load_path(&default_providers_tsv())) {
        Ok(_) => None,
        Err(err) => Some(err),
    }
}

pub fn load_from_path(path: &Path) -> Result<Vec<Entry>, CorpusError> {
    let text = std::fs::read_to_string(path).map_err(|e| CorpusError {
        kind: CORPUS_UNAVAILABLE_KIND,
        message: format!("read {}: {e}", path.display()),
    })?;
    load_from_text(&text).map_err(|e| CorpusError {
        kind: CORPUS_UNAVAILABLE_KIND,
        message: format!("parse {}: {e}", path.display()),
    })
}

pub fn load_from_text(text: &str) -> Result<Vec<Entry>, String> {
    let entries = parse_tsv(text)?;
    if entries.is_empty() {
        return Err("provider corpus has no entries".into());
    }
    Ok(entries)
}

fn load_path(path: &Path) -> Result<Vec<Entry>, CorpusError> {
    load_from_path(path)
}

/// Parse provider rows without touching the filesystem. Invalid rows fail
/// closed so callers cannot silently lose a provider rule.
pub fn parse_tsv(text: &str) -> Result<Vec<Entry>, String> {
    let mut out = Vec::new();
    for (line_no, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split('\t').collect();
        if !(3..=4).contains(&parts.len()) {
            return Err(format!("line {}: need 3 or 4 fields", line_no + 1));
        }
        if parts[0].is_empty() || parts[1].is_empty() {
            return Err(format!(
                "line {}: comm and provider id are required",
                line_no + 1
            ));
        }
        if parts.len() == 4 && !parts[3].is_empty() && parts[3] != "path-segment" {
            return Err(format!(
                "line {}: unknown match {:?}",
                line_no + 1,
                parts[3]
            ));
        }
        out.push(Entry {
            comm: parts[0].to_string(),
            id: parts[1].to_string(),
            display: parts[2].to_string(),
            path_segment: parts.get(3).copied() == Some("path-segment"),
        });
    }
    Ok(out)
}

pub fn basename(comm: &str) -> &str {
    Path::new(comm)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or(comm)
}

pub fn lookup(comm: &str) -> Option<&'static Entry> {
    let base = basename(comm);
    if let Some(e) = load().iter().find(|e| e.comm == base) {
        return Some(e);
    }
    let slash = comm.replace('\\', "/");
    load()
        .iter()
        .find(|e| e.path_segment && !e.comm.is_empty() && slash.contains(&format!("/{}/", e.comm)))
}

pub fn match_comms(comms: &[String]) -> Option<&'static Entry> {
    comms.iter().find_map(|c| lookup(c))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provider_corpus_load_failures_are_structured() {
        let missing =
            std::env::temp_dir().join(format!("nodeprobe-provider-missing-{}", std::process::id()));
        let err = load_from_path(&missing).unwrap_err();
        assert_eq!(err.kind, CORPUS_UNAVAILABLE_KIND);
        assert!(err.message.contains("read"), "{}", err.message);

        let malformed = std::env::temp_dir().join(format!(
            "nodeprobe-provider-malformed-{}",
            std::process::id()
        ));
        std::fs::write(&malformed, "codex\tcodex\tCodex\textra\n").unwrap();
        let err = load_from_path(&malformed).unwrap_err();
        assert_eq!(err.kind, CORPUS_UNAVAILABLE_KIND);
        assert!(err.message.contains("parse"), "{}", err.message);
        let _ = std::fs::remove_file(malformed);
    }

    #[test]
    fn empty_provider_corpus_is_unavailable() {
        assert!(load_from_text("# comments only\n").is_err());
    }

    #[test]
    fn six_rows_and_basename() {
        let t = load();
        assert_eq!(t.len(), 6);
        assert_eq!(lookup("codex").map(|e| e.id.as_str()), Some("codex"));
        let full = "/opt/homebrew/bin/codex";
        assert_ne!(full, "codex");
        assert_eq!(lookup(full).map(|e| e.id.as_str()), Some("codex"));
        assert!(lookup("bash").is_none());
    }

    /// Same comm→id table as server/internal/provider/table_test.go, same TSV.
    #[test]
    fn malformed_provider_rows_fail_closed() {
        assert!(parse_tsv("codex\tcodex\tCodex\textra").is_err());
        assert!(parse_tsv("\tcodex\tCodex").is_err());
        assert!(parse_tsv("codex\tcodex").is_err());
    }

    #[test]
    fn provider_corpus_parity_with_go() {
        let want = [
            ("claude", "claude_code"),
            ("codex", "codex"),
            ("copilot", "copilot"),
            ("grok", "grok"),
            ("cursor-agent", "cursor"),
            ("pi", "pi"),
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
        for noise in ["bash", "sleep", "vim", "make", "sshd", "node", ""] {
            assert!(lookup(noise).is_none(), "noise {noise} must not be a node");
        }
        assert!(
            load().iter().all(|e| e.comm != "node"),
            "TSV must not whitelist basename node"
        );
        let cursor_node =
            "/Users/alauda/.local/share/cursor-agent/versions/2026.08.11-e8db854/node";
        assert_eq!(basename(cursor_node), "node");
        assert_eq!(lookup(cursor_node).map(|e| e.id.as_str()), Some("cursor"));
        assert!(lookup("/Users/alauda/.nvm/versions/node/v20.0.0/bin/node").is_none());
    }
}
