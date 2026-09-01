//! ---
//! purpose: nodeprobe core——聚合 list-panes + 窄字段进程观测，对具体 CLI 文案一无所知
//! contract:
//!   provides:
//!     - name: probe
//!       what: 输出每个席位的四轴状态；加新 provider 不必改本文件
//!   depends:
//!     - classify（provider 匹配器）
//!     - tmux list-panes
//!     - ps pid/ppid/stat/comm snapshot
//!   boundary:
//!   - 只做 list-panes 与窄字段 ps；不 capture-pane、不 attach、不 send-keys。
//!   - 不把 health 折进 activity；不把 unknown 写成 idle/normal
//!   - 不写任何家的页脚短语
//! maturity: wired
//! ---

pub mod classify;
pub mod pi_activity;
pub mod proctree;
pub mod providers;

use classify::{
    classify, format_codepoint, parse_corpus_line, BackgroundTasks, Class, CorpusRow, PROVIDER_PI,
    STATE_UNKNOWN,
};
use serde::Serialize;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

pub const SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Serialize)]
pub struct ProbeError {
    pub kind: String,
    pub message: String,
}

#[derive(Debug, Serialize)]
pub struct Report {
    pub schema_version: u32,
    pub socket: String,
    pub sampled_at: String,
    pub nodes: Vec<Node>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ProbeError>,
}

#[derive(Debug, Serialize)]
pub struct Node {
    pub session: String,
    pub window_index: u32,
    pub window_name: String,
    pub pane_id: String,
    pub name: String,
    pub provider: String,
    /// Backward-compatible alias for `activity`.
    pub state: String,
    pub activity: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_name: Option<String>,
    pub health: String,
    pub background_tasks: BackgroundTasks,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub footer_error: Option<String>,
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

#[derive(Debug, Clone)]
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

/// Classify activity after provider identity has been established. Provider
/// matching and activity classification remain separate operations.
pub fn classify_provider(provider: &str, title: &str) -> Class {
    classify::classify_for(provider, title)
}

/// Return the whitelisted provider id for a comm-only process tree snapshot.
/// Process arguments are intentionally not accepted by this API.
pub fn detect_provider(comms: &[String]) -> Option<String> {
    providers::match_comms(comms).map(|e| e.id.clone())
}

pub fn evidence_for(title: &str, class: &Class, comms: Vec<String>) -> Evidence {
    let detail = if class.provider == PROVIDER_PI {
        "activity=unsupported pi activity channel unavailable".to_string()
    } else {
        "title-only activity".to_string()
    };
    evidence_for_axes(
        title,
        class,
        comms,
        class.state,
        detail,
        pi_activity::HEALTH_UNKNOWN,
    )
}

fn evidence_for_axes(
    title: &str,
    class: &Class,
    comms: Vec<String>,
    activity: &str,
    activity_detail: String,
    health: &str,
) -> Evidence {
    let method = if class.provider == PROVIDER_PI {
        "pi_activity_channel".to_string()
    } else {
        "pane_title".to_string()
    };
    if class.state == STATE_UNKNOWN {
        let glyph = class.first.map(|c| c.to_string());
        let codepoint = class.first.map(format_codepoint);
        let detail = if class.provider == PROVIDER_PI {
            format!(
                "provider=pi activity={} health={} {} title={}",
                activity, health, activity_detail, title
            )
        } else {
            format!(
                "unclaimed leading glyph glyph={} codepoint={} title={}",
                glyph.as_deref().unwrap_or(""),
                codepoint.as_deref().unwrap_or("U+0000"),
                title
            )
        };
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
            "provider={} state={} health={} first={} {} title={}",
            class.provider, class.state, health, first, activity_detail, title
        ),
        glyph: None,
        codepoint: None,
        title: None,
        comms,
    }
}

fn merge_pi_session_name(
    title: Option<&pi_activity::TitleName>,
    channel: Option<String>,
) -> Result<Option<String>, ()> {
    match title {
        Some(pi_activity::TitleName::Parsed(Some(title))) => match channel {
            Some(channel) if channel == *title => Ok(Some(channel)),
            Some(_) | None => Err(()),
        },
        Some(pi_activity::TitleName::Parsed(None)) => match channel {
            None => Ok(None),
            Some(_) => Err(()),
        },
        Some(pi_activity::TitleName::Ambiguous) | None => Ok(channel),
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
        .arg("#{session_name}\u{1f}#{window_index}\u{1f}#{window_name}\u{1f}#{pane_id}\u{1f}#{pane_pid}\u{1f}#{pane_title}");
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
        let fields: Vec<&str> = line.splitn(6, '\u{1f}').collect();
        if fields.len() != 6 {
            return Err(format!(
                "tmux list-panes malformed row: expected 6 fields, got {}",
                fields.len()
            ));
        }
        let session = fields[0].to_string();
        let window_index = fields[1]
            .parse()
            .map_err(|_| format!("tmux list-panes malformed window index {:?}", fields[1]))?;
        let window_name = fields[2].to_string();
        let pane_id = fields[3].to_string();
        let pane_pid = fields[4]
            .parse()
            .map_err(|_| format!("tmux list-panes malformed pane pid {:?}", fields[4]))?;
        let title = fields[5].to_string();
        panes.push(RawPane {
            session,
            window_index,
            window_name,
            pane_id,
            pane_pid,
            title,
        });
    }
    Ok(panes)
}

#[derive(Debug)]
pub struct RawPane {
    pub session: String,
    pub window_index: u32,
    pub window_name: String,
    pub pane_id: String,
    pub pane_pid: i32,
    pub title: String,
}

pub fn probe(spec: SocketSpec) -> Result<Report, String> {
    let panes = list_panes(&spec)?;
    // Force one corpus load and preserve its structured failure as a
    // capability error; provider misses below remain honest `unknown` rows.
    let corpus_error = providers::corpus_error().map(|e| ProbeError {
        kind: e.kind.to_string(),
        message: e.message.clone(),
    });
    let snap = proctree::read_table();
    let mut nodes = Vec::with_capacity(panes.len());
    for p in panes {
        let processes = match snap.as_ref() {
            Some(s) if p.pane_pid > 0 => proctree::walk_processes(s, p.pane_pid),
            _ => Vec::new(),
        };
        let comms: Vec<String> = processes.iter().map(|(_, comm)| comm.clone()).collect();
        let provider_entry = providers::match_comms(&comms);
        let class = if let Some(e) = provider_entry {
            classify::classify_for(&e.id, &p.title)
        } else {
            // Unknown identity is an honest node row, not a silently dropped pane.
            classify::classify(&p.title)
        };
        let pi_pids: Vec<i32> = processes
            .iter()
            .filter(|(_, comm)| providers::lookup(comm).is_some_and(|e| e.id == PROVIDER_PI))
            .map(|(pid, _)| *pid)
            .collect();
        let pi_observation = if class.provider == PROVIDER_PI {
            Some(pi_activity::observe(
                pi_activity::channel_dir().as_deref(),
                &pi_pids,
                pi_activity::now_millis(),
                pi_activity::DEFAULT_MAX_AGE_MS,
            ))
        } else {
            None
        };
        // Pane bodies are intentionally out of scope: health/activity never use
        // footer text and background task count stays unknown without an authority.
        let background_tasks = BackgroundTasks::Unknown;
        let footer_error = None;
        let title_name = if class.provider == PROVIDER_PI {
            pi_activity::title_name(&p.title)
        } else {
            None
        };
        let title_session_name = match &title_name {
            Some(pi_activity::TitleName::Parsed(name)) => name.clone(),
            Some(pi_activity::TitleName::Ambiguous) | None => None,
        };
        let (activity, session_name, health, activity_detail) = match pi_observation {
            Some(observation) => {
                if !observation.channel_available {
                    (
                        observation.activity,
                        title_session_name,
                        observation.health,
                        observation.detail,
                    )
                } else {
                    match merge_pi_session_name(
                        title_name.as_ref(),
                        observation.session_name.clone(),
                    ) {
                        Ok(session_name) => (
                            observation.activity,
                            session_name,
                            observation.health,
                            observation.detail,
                        ),
                        Err(()) => (
                            pi_activity::ACTIVITY_UNKNOWN,
                            None,
                            pi_activity::HEALTH_UNKNOWN,
                            format!(
                                "{} session_name conflict: title/channel disagree",
                                observation.detail
                            ),
                        ),
                    }
                }
            }
            None => (
                class.state,
                title_session_name,
                pi_activity::HEALTH_UNKNOWN,
                "activity channel unavailable for non-Pi provider".to_string(),
            ),
        };
        let evidence =
            evidence_for_axes(&p.title, &class, comms, activity, activity_detail, health);
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
            state: activity.to_string(),
            activity: activity.to_string(),
            session_name,
            health: health.to_string(),
            background_tasks,
            footer_error,
            evidence,
        });
    }
    Ok(Report {
        schema_version: SCHEMA_VERSION,
        socket: spec.display().to_string(),
        sampled_at: sampled_at_utc(),
        nodes,
        error: corpus_error,
    })
}

pub fn error_report(spec: &SocketSpec, message: String) -> Report {
    Report {
        schema_version: SCHEMA_VERSION,
        socket: spec.display().to_string(),
        sampled_at: sampled_at_utc(),
        nodes: Vec::new(),
        error: Some(ProbeError {
            kind: "tmux_inventory".to_string(),
            message,
        }),
    }
}

pub fn run_fixtures(path: &Path) -> Result<String, String> {
    let text =
        std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    let mut out = String::new();
    for (i, line) in text.lines().enumerate() {
        let row = match parse_corpus_line(line) {
            Ok(None) => continue,
            Ok(Some(r)) => r,
            Err(e) => return Err(format!("line {}: {e}", i + 1)),
        };
        match row {
            CorpusRow::Title {
                payload,
                got,
                want_state,
                want_provider,
            } => {
                if got.state != want_state || got.provider != want_provider {
                    return Err(format!(
                        "line {} title={payload:?} want {want_state}/{want_provider} got {}/{}",
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
            CorpusRow::Footer {
                payload,
                got,
                want,
                provider,
            } => {
                if got != want {
                    return Err(format!(
                        "line {} footer={payload:?} provider={provider} want {want:?} got {got:?}",
                        i + 1
                    ));
                }
                match got {
                    BackgroundTasks::Count(n) => out.push_str(&n.to_string()),
                    BackgroundTasks::Unknown => out.push_str("unknown"),
                }
                out.push('\t');
                out.push_str(&provider);
                out.push('\n');
            }
        }
    }
    Ok(out)
}

pub fn default_fixtures() -> PathBuf {
    std::env::var_os("NODEPROBE_FIXTURES")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/titles.tsv"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixtures_corpus() {
        let path = default_fixtures();
        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
        let mut n_title = 0usize;
        let mut n_footer = 0usize;
        for (i, line) in text.lines().enumerate() {
            let row = parse_corpus_line(line).unwrap_or_else(|e| panic!("line {}: {e}", i + 1));
            let Some(row) = row else {
                continue;
            };
            match row {
                CorpusRow::Title {
                    payload,
                    got,
                    want_state,
                    want_provider,
                } => {
                    n_title += 1;
                    assert_eq!(
                        got.state,
                        want_state,
                        "line {} title={payload:?} state {} != {want_state} first={:?}",
                        i + 1,
                        got.state,
                        got.first
                    );
                    assert_eq!(
                        got.provider,
                        want_provider,
                        "line {} title={payload:?} provider {} != {want_provider}",
                        i + 1,
                        got.provider
                    );
                    if got.state == STATE_UNKNOWN {
                        assert!(got.first.is_some(), "unknown must carry a leading glyph");
                        let ev = evidence_for(&payload, &got, Vec::new());
                        assert!(ev.codepoint.is_some(), "unknown evidence missing codepoint");
                        assert_eq!(ev.title.as_deref(), Some(payload.as_str()));
                    }
                }
                CorpusRow::Footer {
                    payload,
                    got,
                    want,
                    provider,
                } => {
                    n_footer += 1;
                    assert_eq!(
                        got,
                        want,
                        "line {} footer={payload:?} provider={provider} {got:?} != {want:?}",
                        i + 1
                    );
                }
            }
        }
        assert!(n_title >= 6, "title rows {n_title} < 6");
        assert!(n_footer >= 3, "footer rows {n_footer} < 3");
        eprintln!("fixtures_corpus title={n_title} footer={n_footer} ok");
    }

    #[test]
    fn corpus_rejects_missing_kind() {
        let err = parse_corpus_line("hello\tidle\tgrok").unwrap_err();
        assert!(err.contains("4 fields"), "{err}");
    }

    #[test]
    fn malformed_corpus_kind_is_not_ignored() {
        let err = parse_corpus_line("hello\tidle\tgrok\tother").unwrap_err();
        assert!(err.contains("unknown kind"), "{err}");
    }

    #[test]
    fn fixture_runs_are_repeatable_and_provider_is_separate() {
        let path = default_fixtures();
        assert_eq!(run_fixtures(&path).unwrap(), run_fixtures(&path).unwrap());
        assert_eq!(classify_title("hello").provider, "unknown");
        assert_eq!(classify_provider("codex", "Codex CLI").provider, "codex");
        assert_eq!(detect_provider(&["/bin/bash".into()]), None);
    }

    #[test]
    fn pi_unsupported_activity_is_explicit_in_evidence() {
        let title = "π - pi-activity-sample-subject-luna - 多agent协作";
        let class = classify_provider("pi", title);
        let evidence = evidence_for(title, &class, vec!["pi".into()]);
        assert_eq!(class.provider, "pi");
        assert_eq!(class.state, "unknown");
        assert!(evidence.detail.contains("activity=unsupported"));
        assert_eq!(evidence.codepoint.as_deref(), Some("U+03C0"));
    }

    #[test]
    fn pi_session_name_merge_requires_agreement_or_single_authority() {
        use pi_activity::TitleName;
        let three = TitleName::Parsed(Some("build-main".into()));
        let two = TitleName::Parsed(None);
        assert_eq!(
            merge_pi_session_name(Some(&three), Some("build-main".into())),
            Ok(Some("build-main".into()))
        );
        assert_eq!(merge_pi_session_name(Some(&two), None), Ok(None));
        assert_eq!(
            merge_pi_session_name(None, Some("channel-only".into())),
            Ok(Some("channel-only".into()))
        );
        assert_eq!(
            merge_pi_session_name(Some(&three), Some("other".into())),
            Err(())
        );
        assert_eq!(
            merge_pi_session_name(Some(&two), Some("named".into())),
            Err(())
        );
    }

    #[test]
    fn pi_channel_evidence_matches_authoritative_activity_and_health() {
        let title = "π - build-main - repo";
        let class = classify_provider("pi", title);
        for (activity, health) in [
            (pi_activity::ACTIVITY_WORKING, pi_activity::HEALTH_NORMAL),
            (pi_activity::ACTIVITY_IDLE, pi_activity::HEALTH_NORMAL),
            (pi_activity::ACTIVITY_UNKNOWN, pi_activity::HEALTH_UNKNOWN),
        ] {
            let detail = if activity == pi_activity::ACTIVITY_UNKNOWN {
                "pi activity channel unavailable"
            } else {
                "pi activity channel"
            };
            let evidence = evidence_for_axes(
                title,
                &class,
                vec!["pi".into()],
                activity,
                detail.into(),
                health,
            );
            assert!(
                evidence.detail.contains(&format!("activity={activity}")),
                "{}",
                evidence.detail
            );
            assert!(
                evidence.detail.contains(&format!("health={health}")),
                "{}",
                evidence.detail
            );
        }
    }

    #[test]
    fn missing_socket_is_not_empty_success() {
        let path = format!("/tmp/nodeprobe-missing-{}", std::process::id());
        let err = list_panes(&SocketSpec::Path(path.into())).unwrap_err();
        assert!(err.contains("tmux list-panes failed"), "{err}");
    }

    #[test]
    fn error_report_is_versioned_and_explicit() {
        let r = error_report(
            &SocketSpec::Path("/missing/socket".into()),
            "missing".into(),
        );
        let json = serde_json::to_string(&r).unwrap();
        assert!(json.contains("\"schema_version\":1"));
        assert!(json.contains("\"kind\":\"tmux_inventory\""));
        assert!(json.contains("\"error\""));
    }
}
