//! Walk pane_pid plus descendants using one `ps -axo pid=,ppid=,stat=,comm=` snapshot.
//! Never reads process argument vectors. Narrow ps fields only.

use std::collections::HashMap;
use std::process::Command;

#[derive(Debug, Default)]
pub struct Snap {
    pub comm: HashMap<i32, String>,
    pub kids: HashMap<i32, Vec<i32>>,
    pub stat: HashMap<i32, String>,
}

pub fn read_table() -> Option<Snap> {
    let out = Command::new("ps")
        .arg("-axo")
        .arg("pid=,ppid=,stat=,comm=")
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    Some(parse_table(&String::from_utf8_lossy(&out.stdout)))
}

fn looks_like_stat(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 8
        && !s.contains('/')
        && s.chars()
            .all(|c| c.is_ascii_alphabetic() || c == '+' || c == '<' || c == '>' || c == '?')
}

pub fn parse_table(text: &str) -> Snap {
    let mut s = Snap::default();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 3 {
            continue;
        }
        let Ok(pid) = fields[0].parse::<i32>() else {
            continue;
        };
        let Ok(ppid) = fields[1].parse::<i32>() else {
            continue;
        };
        let (stat, comm) = if fields.len() >= 4 && looks_like_stat(fields[2]) {
            (fields[2].to_string(), fields[3..].join(" "))
        } else {
            (String::new(), fields[2..].join(" "))
        };
        s.comm.insert(pid, comm);
        if !stat.is_empty() {
            s.stat.insert(pid, stat);
        }
        s.kids.entry(ppid).or_default().push(pid);
    }
    s
}

/// Root-to-descendant comms (raw ps comm, basename applied at lookup).
pub fn walk_comms(s: &Snap, root: i32) -> Vec<String> {
    let mut out = Vec::new();
    fn walk(s: &Snap, pid: i32, out: &mut Vec<String>) {
        if let Some(c) = s.comm.get(&pid) {
            out.push(c.clone());
        }
        if let Some(kids) = s.kids.get(&pid) {
            for kid in kids {
                walk(s, *kid, out);
            }
        }
    }
    walk(s, root, &mut out);
    out
}

fn is_foreground(stat: &str) -> bool {
    stat.contains('+')
}

/// Identity walk: prefer processes in the tty foreground group (`stat` contains
/// `+`). A leftover Agent child after Ctrl+C/back-to-shell is typically `SN`
/// while zsh holds `+`; matching it would keep listing an Agent icon on a
/// shell row. Nested wrappers (bash waiting, Agent with `+`) still match.
/// If the snapshot has no foreground bit (legacy tables without `stat`),
/// fall open to the full descendant walk so identity does not go blank.
pub fn walk_identity_comms(s: &Snap, root: i32) -> Vec<String> {
    let mut fg = Vec::new();
    fn walk(s: &Snap, pid: i32, fg: &mut Vec<String>) {
        let stat = s.stat.get(&pid).map(String::as_str).unwrap_or("");
        if is_foreground(stat) {
            if let Some(c) = s.comm.get(&pid) {
                fg.push(c.clone());
            }
        }
        if let Some(kids) = s.kids.get(&pid) {
            for kid in kids {
                walk(s, *kid, fg);
            }
        }
    }
    walk(s, root, &mut fg);
    if fg.is_empty() {
        walk_comms(s, root)
    } else {
        fg
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::providers;

    #[test]
    fn walk_hits_nested_codex_via_basename() {
        let snap = parse_table(
            "\
1 0 /sbin/launchd
10 1 /bin/bash
11 10 /opt/homebrew/bin/codex
12 1 /bin/sleep
",
        );
        let comms = walk_comms(&snap, 10);
        assert_eq!(
            comms,
            vec!["/bin/bash".to_string(), "/opt/homebrew/bin/codex".to_string()]
        );
        let e = providers::match_comms(&comms).expect("codex behind bash");
        assert_eq!(e.id, "codex");
        assert!(providers::match_comms(&walk_comms(&snap, 12)).is_none());
    }

    #[test]
    fn ps_invocation_is_narrow_fields_only() {
        let prod = include_str!("proctree.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(prod.contains("pid=,ppid=,stat=,comm="));
        assert!(!prod.contains("args="));
        assert!(!prod.contains("command="));
        assert!(!prod.contains("arg(\"-f\")"));
    }

    #[test]
    fn leftover_background_agent_is_not_identity() {
        let snap = parse_table(
            "\
1 0 Ss /sbin/launchd
10 1 Ss+ /bin/zsh
11 10 SN /opt/homebrew/bin/grok
",
        );
        let full = walk_comms(&snap, 10);
        assert!(
            providers::match_comms(&full).is_some(),
            "full descendant walk still sees leftover grok (old bug)"
        );
        let ident = walk_identity_comms(&snap, 10);
        assert_eq!(ident, vec!["/bin/zsh".to_string()]);
        assert!(
            providers::match_comms(&ident).is_none(),
            "background grok must not keep the pane identified as Agent"
        );
    }

    #[test]
    fn nested_foreground_codex_still_identified() {
        let snap = parse_table(
            "\
1 0 Ss /sbin/launchd
10 1 Ss /bin/bash
11 10 S+ /opt/homebrew/bin/codex
",
        );
        let ident = walk_identity_comms(&snap, 10);
        let e = providers::match_comms(&ident).expect("codex in foreground group");
        assert_eq!(e.id, "codex");
    }
}