//! Walk pane_pid plus descendants using one `ps -axo pid=,ppid=,comm=` snapshot.
//! Never reads process argument vectors. Narrow ps fields only.

use std::collections::HashMap;
use std::process::Command;

#[derive(Debug, Default)]
pub struct Snap {
    pub comm: HashMap<i32, String>,
    pub kids: HashMap<i32, Vec<i32>>,
}

pub fn read_table() -> Option<Snap> {
    let out = Command::new("ps")
        .arg("-axo")
        .arg("pid=,ppid=,comm=")
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    Some(parse_table(&String::from_utf8_lossy(&out.stdout)))
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
        let comm = fields[2..].join(" ");
        s.comm.insert(pid, comm);
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
        assert!(prod.contains("pid=,ppid=,comm="));
        assert!(!prod.contains("args="));
        assert!(!prod.contains("command="));
        assert!(!prod.contains("arg(\"-f\")"));
    }
}
