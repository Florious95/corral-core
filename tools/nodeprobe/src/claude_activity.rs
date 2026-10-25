//! ---
//! purpose: Claude Code 自写的会话登记（<sessions>/<pid>.json 的 status）→ activity
//! contract:
//!   provides:
//!     - name: observe
//!       what: 按 pane 进程树里的 claude PID 读登记；busy=working，idle/waiting/shell=idle，
//!             登记在但读不懂 ⇒ unknown；没有可采信的登记 ⇒ Err(理由)，调用方退回标题
//!   depends:
//!     - $NODEPROBE_CLAUDE_SESSIONS_DIR，缺省 $HOME/.claude/sessions（Claude Code 2.1.x 自写）
//! boundary:
//!   - 只读以 PID 命名的 <pid>.json；不读 transcript、pane body、argv，同目录 *.key 不碰
//!   - 登记的 tmux 坐标不是本 pane ⇒ 视为 PID 复用的旧登记，不采信
//! maturity: wired
//! ---
//! Why a channel: inside tmux Claude Code pins its title glyph to a static ✳
//! (flag `tengu_static_title_under_mux`, default on), so the title cannot tell
//! working from idle. The REPL rewrites `status` in this registry on every
//! busy/idle/waiting change; that is the same state that drives its spinner.

use crate::classify::{STATE_IDLE, STATE_UNKNOWN, STATE_WORKING};
use serde::Deserialize;
use std::path::{Path, PathBuf};

pub const METHOD: &str = "claude_session_registry";

#[derive(Debug, Deserialize)]
struct Record {
    pid: i64,
    status: String,
    #[serde(default)]
    tmux: Option<String>,
    #[serde(default, rename = "waitingFor")]
    waiting_for: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Observation {
    pub activity: &'static str,
    pub detail: String,
}

pub fn sessions_dir() -> Option<PathBuf> {
    std::env::var_os("NODEPROBE_CLAUDE_SESSIONS_DIR")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|home| Path::new(&home).join(".claude/sessions")))
}

/// `tmux` is "<session>:@<window_id>.%<pane_id>"; only the pane id is stable
/// (sessions can be renamed after Claude registered).
fn tmux_pane(tmux: &str) -> Option<&str> {
    tmux.rsplit_once('.').map(|(_, pane)| pane)
}

fn activity_for(status: &str) -> &'static str {
    match status {
        "busy" => STATE_WORKING,
        "idle" | "waiting" | "shell" => STATE_IDLE,
        _ => STATE_UNKNOWN,
    }
}

/// `claude_pids` are in root-first walk order, so an outer interactive session
/// wins over a nested `claude` it spawned.
pub fn observe(
    dir: Option<&Path>,
    claude_pids: &[i32],
    pane_id: &str,
) -> Result<Observation, String> {
    let dir = dir.ok_or("no sessions dir (HOME unset)")?;
    if claude_pids.is_empty() {
        return Err("no claude pid in pane tree".into());
    }
    let mut skipped = Vec::new();
    for &pid in claude_pids {
        let path = dir.join(format!("{pid}.json"));
        let text = match std::fs::read_to_string(&path) {
            Ok(text) => text,
            Err(e) => {
                skipped.push(format!("pid={pid} {e}"));
                continue;
            }
        };
        let record: Record = match serde_json::from_str(&text) {
            Ok(r) => r,
            Err(e) => {
                return Ok(Observation {
                    activity: STATE_UNKNOWN,
                    detail: format!("{METHOD} pid={pid} unreadable record: {e}"),
                })
            }
        };
        if record.pid != i64::from(pid) {
            skipped.push(format!("pid={pid} record_pid={}", record.pid));
            continue;
        }
        if let Some(tmux) = record.tmux.as_deref() {
            if tmux_pane(tmux) != Some(pane_id) {
                skipped.push(format!("pid={pid} record_tmux={tmux} pane={pane_id}"));
                continue;
            }
        }
        let activity = activity_for(&record.status);
        return Ok(Observation {
            activity,
            detail: format!(
                "{METHOD} pid={pid} status={} waiting_for={} -> {activity}",
                record.status,
                record.waiting_for.as_deref().unwrap_or("none")
            ),
        });
    }
    Err(skipped.join("; "))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn dir(name: &str) -> PathBuf {
        let d =
            std::env::temp_dir().join(format!("nodeprobe-claude-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn write(d: &Path, pid: i32, body: &str) {
        fs::write(d.join(format!("{pid}.json")), body).unwrap();
    }

    #[test]
    fn registry_status_maps_to_activity() {
        let d = dir("map");
        for (status, want) in [
            ("busy", STATE_WORKING),
            ("idle", STATE_IDLE),
            ("waiting", STATE_IDLE),
            ("shell", STATE_IDLE),
            ("dreaming", STATE_UNKNOWN),
        ] {
            write(
                &d,
                7,
                &format!(
                    r#"{{"pid":7,"status":"{status}","tmux":"s:@1.%3","waitingFor":"permission prompt","name":"x"}}"#
                ),
            );
            let got = observe(Some(&d), &[7], "%3").unwrap();
            assert_eq!(got.activity, want, "status={status}");
            assert!(
                got.detail.contains(&format!("status={status}")),
                "{}",
                got.detail
            );
        }
        let _ = fs::remove_dir_all(d);
    }

    #[test]
    fn foreign_or_missing_record_falls_back_to_title() {
        let d = dir("foreign");
        assert!(observe(Some(&d), &[8], "%3").unwrap_err().contains("pid=8"));
        write(&d, 8, r#"{"pid":8,"status":"busy","tmux":"s:@1.%30"}"#);
        let err = observe(Some(&d), &[8], "%3").unwrap_err();
        assert!(err.contains("record_tmux=s:@1.%30 pane=%3"), "{err}");
        write(&d, 8, r#"{"pid":9,"status":"busy"}"#);
        assert!(observe(Some(&d), &[8], "%3")
            .unwrap_err()
            .contains("record_pid=9"));
        assert!(observe(None, &[8], "%3").is_err());
        assert!(observe(Some(&d), &[], "%3").is_err());
        let _ = fs::remove_dir_all(d);
    }

    #[test]
    fn malformed_record_is_unknown_not_idle() {
        let d = dir("bad");
        write(&d, 5, r#"{"pid":5,"stat"#);
        assert_eq!(
            observe(Some(&d), &[5], "%1").unwrap().activity,
            STATE_UNKNOWN
        );
        let _ = fs::remove_dir_all(d);
    }

    #[test]
    fn outer_session_wins_and_missing_tmux_binds_by_pid() {
        let d = dir("order");
        write(&d, 10, r#"{"pid":10,"status":"busy"}"#);
        write(&d, 11, r#"{"pid":11,"status":"idle","tmux":"s:@1.%2"}"#);
        assert_eq!(
            observe(Some(&d), &[10, 11], "%2").unwrap().activity,
            STATE_WORKING
        );
        assert_eq!(
            observe(Some(&d), &[12, 11], "%2").unwrap().activity,
            STATE_IDLE
        );
        let _ = fs::remove_dir_all(d);
    }
}
