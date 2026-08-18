pub mod classify;

use classify::{classify, format_codepoint, Class, STATE_UNKNOWN};
use serde::Serialize;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Serialize)]
pub struct Report {
    pub socket: String,
    pub sampled_at: String,
    pub nodes: Vec<Node>,
}

#[derive(Debug, Serialize)]
pub struct Node {
    pub session: String,
    pub window_index: u32,
    pub window_name: String,
    pub pane_id: String,
    pub name: String,
    pub provider: String,
    pub state: String,
    pub evidence: Evidence,
}

#[derive(Debug, Serialize)]
pub struct Evidence {
    pub method: String,
    pub detail: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub glyph: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codepoint: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub comms: Vec<String>,
}

pub enum SocketSpec {
    Path(String),
    Name(String),
}

impl SocketSpec {
    pub fn display(&self) -> &str {
        match self {
            SocketSpec::Path(s) | SocketSpec::Name(s) => s,
        }
    }
}

pub fn classify_title(title: &str) -> Class {
    classify(title)
}

pub fn evidence_for(title: &str, class: &Class, comms: Vec<String>) -> Evidence {
    let method = "pane_title".to_string();
    if class.state == STATE_UNKNOWN {
        let glyph = class.first.map(|c| c.to_string());
        let codepoint = class.first.map(format_codepoint);
        let detail = format!(
            "unclaimed leading glyph glyph={} codepoint={} title={}",
            glyph.as_deref().unwrap_or(""),
            codepoint.as_deref().unwrap_or("U+0000"),
            title
        );
        return Evidence {
            method,
            detail,
            glyph,
            codepoint,
            title: Some(title.to_string()),
            comms,
        };
    }
    let first = class
        .first
        .map(|c| format!("{} {}", format_codepoint(c), c))
        .unwrap_or_else(|| "none".into());
    Evidence {
        method,
        detail: format!(
            "provider={} state={} first={} title={}",
            class.provider, class.state, first, title
        ),
        glyph: None,
        codepoint: None,
        title: None,
        comms,
    }
}

fn sampled_at_utc() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // date(1) keeps this crate free of a clock dependency; failure → epoch.
    let out = Command::new("date")
        .arg("-u")
        .arg("-r")
        .arg(secs.to_string())
        .arg("+%Y-%m-%dT%H:%M:%SZ")
        .output();
    match out {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout).trim().to_string(),
        _ => {
            // GNU date uses -d @secs; macOS uses -r.
            let out = Command::new("date")
                .arg("-u")
                .arg(format!("-d@{secs}"))
                .arg("+%Y-%m-%dT%H:%M:%SZ")
                .output();
            match out {
                Ok(o) if o.status.success() => {
                    String::from_utf8_lossy(&o.stdout).trim().to_string()
                }
                _ => format!("{secs}"),
            }
        }
    }
}

fn tmux_base(spec: &SocketSpec) -> Command {
    let mut cmd = Command::new("tmux");
    match spec {
        SocketSpec::Path(p) => {
            cmd.arg("-S").arg(p);
        }
        SocketSpec::Name(n) => {
            cmd.arg("-L").arg(n);
        }
    }
    cmd
}

/// list-panes only. Never attach, never send keys, never change pane state.
pub fn list_panes(spec: &SocketSpec) -> Result<Vec<RawPane>, String> {
    let mut cmd = tmux_base(spec);
    cmd.arg("list-panes")
        .arg("-a")
        .arg("-F")
        .arg("#{session_name}\u{1f}#{window_index}\u{1f}#{window_name}\u{1f}#{pane_id}\u{1f}#{pane_tty}\u{1f}#{pane_title}");
    let out = cmd
        .output()
        .map_err(|e| format!("tmux list-panes spawn: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "tmux list-panes failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let mut panes = Vec::new();
    for line in text.lines() {
        if line.is_empty() {
            continue;
        }
        let mut it = line.splitn(6, '\u{1f}');
        let session = it.next().unwrap_or("").to_string();
        let window_index = it.next().unwrap_or("0").parse().unwrap_or(0);
        let window_name = it.next().unwrap_or("").to_string();
        let pane_id = it.next().unwrap_or("").to_string();
        let pane_tty = it.next().unwrap_or("").to_string();
        let title = it.next().unwrap_or("").to_string();
        panes.push(RawPane {
            session,
            window_index,
            window_name,
            pane_id,
            pane_tty,
            title,
        });
    }
    Ok(panes)
}

pub struct RawPane {
    pub session: String,
    pub window_index: u32,
    pub window_name: String,
    pub pane_id: String,
    pub pane_tty: String,
    pub title: String,
}

/// comm names on this tty only (basename). Never reads process argument vectors.
pub fn comms_on_tty(tty: &str) -> Vec<String> {
    let spec = tty.trim().strip_prefix("/dev/").unwrap_or(tty.trim());
    if spec.is_empty() {
        return Vec::new();
    }
    let out = Command::new("ps")
        .arg("-t")
        .arg(spec)
        .arg("-o")
        .arg("comm=")
        .output();
    let Ok(o) = out else {
        return Vec::new();
    };
    if !o.status.success() {
        return Vec::new();
    }
    String::from_utf8_lossy(&o.stdout)
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            Path::new(s)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or(s)
                .to_string()
        })
        .collect()
}

pub fn probe(spec: SocketSpec) -> Result<Report, String> {
    let panes = list_panes(&spec)?;
    let mut nodes = Vec::with_capacity(panes.len());
    for p in panes {
        let class = classify(&p.title);
        let comms = comms_on_tty(&p.pane_tty);
        let evidence = evidence_for(&p.title, &class, comms);
        let name = if p.window_name.is_empty() {
            p.session.clone()
        } else {
            p.window_name.clone()
        };
        nodes.push(Node {
            session: p.session,
            window_index: p.window_index,
            window_name: p.window_name,
            pane_id: p.pane_id,
            name,
            provider: class.provider.to_string(),
            state: class.state.to_string(),
            evidence,
        });
    }
    Ok(Report {
        socket: spec.display().to_string(),
        sampled_at: sampled_at_utc(),
        nodes,
    })
}

pub fn run_fixtures(path: &Path) -> Result<String, String> {
    let text =
        std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    let mut out = String::new();
    for (i, line) in text.lines().enumerate() {
        let Some((title, got, want_state, want_provider)) = classify::classify_tsv_line(line)
        else {
            continue;
        };
        if got.state != want_state || got.provider != want_provider {
            return Err(format!(
                "line {} title={title:?} want {want_state}/{want_provider} got {}/{}",
                i + 1,
                got.state,
                got.provider
            ));
        }
        out.push_str(got.state);
        out.push('\t');
        out.push_str(got.provider);
        out.push('\n');
    }
    Ok(out)
}

pub fn default_fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/titles.tsv")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixtures_corpus() {
        let path = default_fixtures();
        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
        let mut n = 0usize;
        for (i, line) in text.lines().enumerate() {
            let Some((title, got, want_state, want_provider)) = classify::classify_tsv_line(line)
            else {
                continue;
            };
            n += 1;
            assert_eq!(
                got.state,
                want_state,
                "line {} title={title:?} state {} != {want_state} first={:?}",
                i + 1,
                got.state,
                got.first
            );
            assert_eq!(
                got.provider,
                want_provider,
                "line {} title={title:?} provider {} != {want_provider}",
                i + 1,
                got.provider
            );
            if got.state == STATE_UNKNOWN {
                assert!(got.first.is_some(), "unknown must carry a leading glyph");
                let ev = evidence_for(&title, &got, Vec::new());
                assert!(ev.codepoint.is_some(), "unknown evidence missing codepoint");
                assert_eq!(ev.title.as_deref(), Some(title.as_str()));
            }
        }
        assert!(n >= 6, "corpus rows {n} < 6");
        eprintln!("fixtures_corpus {n} ok");
    }
}
