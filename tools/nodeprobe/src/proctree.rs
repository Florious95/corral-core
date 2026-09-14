//! Walk pane_pid plus descendants using one `ps` snapshot of pid, ppid, stat,
//! comm, and args. Args are used only for explicit generic-process argv gates
//! and are never included in node evidence.

use std::collections::{HashMap, HashSet};
use std::process::Command;

#[derive(Debug, Default)]
pub struct Snap {
    pub comm: HashMap<i32, String>,
    pub argv: HashMap<i32, String>,
    pub kids: HashMap<i32, Vec<i32>>,
    pub stat: HashMap<i32, String>,
}

pub fn read_table() -> Option<Snap> {
    let out = Command::new("ps")
        .arg("-axo")
        .arg("pid=,ppid=,stat=,comm=,args=")
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
        let (stat, comm_index) = if fields.len() >= 4 && looks_like_stat(fields[2]) {
            (fields[2].to_string(), 3)
        } else {
            (String::new(), 2)
        };
        let comm = fields[comm_index].to_string();
        let argv = fields[comm_index + 1..].join(" ");
        s.comm.insert(pid, comm);
        s.argv.insert(pid, argv);
        if !stat.is_empty() {
            s.stat.insert(pid, stat);
        }
        s.kids.entry(ppid).or_default().push(pid);
    }
    s
}

/// Hard cap for hostile/synthetic process graphs. Normal pane trees are tiny.
pub const MAX_WALK_NODES: usize = 4096;

/// Root-to-descendant process identity (raw ps comm and command line).
pub fn walk_processes_with_argv(s: &Snap, root: i32) -> Vec<(i32, String, String)> {
    let mut out = Vec::new();
    let mut stack = vec![root];
    let mut seen = HashSet::new();
    while let Some(pid) = stack.pop() {
        if !seen.insert(pid) {
            continue;
        }
        if seen.len() > MAX_WALK_NODES {
            break;
        }
        if let Some(c) = s.comm.get(&pid) {
            out.push((
                pid,
                c.clone(),
                s.argv.get(&pid).cloned().unwrap_or_default(),
            ));
        }
        if let Some(kids) = s.kids.get(&pid) {
            // Reverse push preserves the original ps child order in the DFS.
            stack.extend(kids.iter().rev().copied());
        }
    }
    out
}

/// Root-to-descendant comms (raw ps comm, basename applied at lookup).
pub fn walk_processes(s: &Snap, root: i32) -> Vec<(i32, String)> {
    walk_processes_with_argv(s, root)
        .into_iter()
        .map(|(pid, comm, _)| (pid, comm))
        .collect()
}

fn is_shell_basename(cmd: &str) -> bool {
    matches!(
        crate::providers::basename(cmd),
        "zsh" | "bash" | "sh" | "fish" | "dash" | "ksh" | "csh" | "tcsh"
    )
}

fn comms_of(processes: &[(i32, String)]) -> Vec<String> {
    processes.iter().map(|(_, comm)| comm.clone()).collect()
}

fn provider_processes(processes: &[(i32, String, String)]) -> Vec<crate::providers::Process> {
    processes
        .iter()
        .map(|(_, comm, argv)| crate::providers::Process {
            comm: comm.clone(),
            argv: argv.clone(),
        })
        .collect()
}

/// Select one bounded identity set for both provider and Pi PID matching.
/// Foreground Agent identity wins; a shell-held `+` with a non-shell pane
/// command retains a still-running descendant, while a shell pane suppresses
/// Ctrl+C leftovers. No second unbounded traversal is permitted.
pub fn walk_identity_processes_with_argv(
    s: &Snap,
    root: i32,
    current_command: &str,
) -> Vec<(i32, String, String)> {
    let full = walk_processes_with_argv(s, root);
    let fg: Vec<(i32, String, String)> = full
        .iter()
        .filter(|(pid, _, _)| s.stat.get(pid).is_some_and(|stat| stat.contains('+')))
        .cloned()
        .collect();
    if crate::providers::match_processes(&provider_processes(&fg)).is_some() {
        return fg;
    }
    if fg.is_empty() {
        return full;
    }
    if is_shell_basename(current_command) {
        fg
    } else {
        full
    }
}

pub fn walk_identity_processes(s: &Snap, root: i32, current_command: &str) -> Vec<(i32, String)> {
    walk_identity_processes_with_argv(s, root, current_command)
        .into_iter()
        .map(|(pid, comm, _)| (pid, comm))
        .collect()
}

pub fn walk_identity_comms(s: &Snap, root: i32, current_command: &str) -> Vec<String> {
    comms_of(&walk_identity_processes(s, root, current_command))
}

pub fn walk_comms(s: &Snap, root: i32) -> Vec<String> {
    comms_of(&walk_processes(s, root))
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
            vec![
                "/bin/bash".to_string(),
                "/opt/homebrew/bin/codex".to_string()
            ]
        );
        let e = providers::match_comms(&comms).expect("codex behind bash");
        assert_eq!(e.id, "codex");
        assert!(providers::match_comms(&walk_comms(&snap, 12)).is_none());
    }

    #[test]
    fn walk_terminates_on_cycles_and_preserves_first_visit_order() {
        let snap = parse_table(
            "\
10 11 /bin/root
11 10 /bin/child
12 10 /bin/leaf
",
        );
        assert_eq!(
            walk_comms(&snap, 10),
            vec!["/bin/root", "/bin/child", "/bin/leaf"]
        );
    }

    #[test]
    fn walk_is_bounded_for_deep_process_graphs() {
        let mut text = String::new();
        for pid in 1..=(MAX_WALK_NODES as i32 + 10) {
            let ppid = if pid == 1 { 0 } else { pid - 1 };
            text.push_str(&format!("{pid} {ppid} /bin/p{pid}\n"));
        }
        let snap = parse_table(&text);
        let comms = walk_comms(&snap, 1);
        assert_eq!(comms.len(), MAX_WALK_NODES);
    }

    #[test]
    fn ps_invocation_uses_identity_fields_only() {
        let prod = include_str!("proctree.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(prod.contains("pid=,ppid=,stat=,comm=,args="));
        assert!(!prod.contains("command="));
        assert!(!prod.contains("arg(\"-f\")"));
    }

    #[test]
    fn cursor_agent_wrapper_is_matched_by_argv() {
        let snap = parse_table(
            "\
1 0 Ss /sbin/launchd /sbin/launchd
10 1 Ss+ /bin/zsh /bin/zsh
11 10 S /Users/alauda/.local/bin/agent /Users/alauda/.local/bin/agent cursor-agent/index.js
12 10 S /Users/alauda/.local/bin/agent /Users/alauda/.local/bin/agent unrelated.js
",
        );
        let identity = walk_identity_processes_with_argv(&snap, 10, "agent");
        let identity_provider_processes = provider_processes(&identity);
        assert_eq!(
            providers::match_processes(&identity_provider_processes).map(|e| e.id.as_str()),
            Some("cursor")
        );
        let unrelated = walk_identity_processes_with_argv(&snap, 12, "agent");
        assert!(providers::match_processes(&provider_processes(&unrelated)).is_none());
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
        assert!(providers::match_comms(&full).is_some());
        let ident = walk_identity_comms(&snap, 10, "zsh");
        assert_eq!(ident, vec!["/bin/zsh".to_string()]);
        assert!(providers::match_comms(&ident).is_none());
    }

    #[test]
    fn running_agent_still_identified_when_shell_holds_plus() {
        let snap = parse_table(
            "\
1 0 Ss /sbin/launchd
10 1 Ss+ /bin/zsh
11 10 S /opt/homebrew/bin/grok
",
        );
        let ident = walk_identity_comms(&snap, 10, "grok");
        assert_eq!(
            providers::match_comms(&ident).map(|e| e.id.as_str()),
            Some("grok")
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
        let ident = walk_identity_comms(&snap, 10, "codex");
        assert_eq!(
            providers::match_comms(&ident).map(|e| e.id.as_str()),
            Some("codex")
        );
    }

    #[test]
    fn identity_processes_drive_provider_and_pid_selection_together() {
        let snap = parse_table(
            "\
1 0 Ss /sbin/launchd
10 1 Ss+ /bin/zsh
11 10 SN /opt/homebrew/bin/pi
",
        );
        let shell_identity = walk_identity_processes(&snap, 10, "zsh");
        assert_eq!(shell_identity, vec![(10, "/bin/zsh".to_string())]);
        assert!(providers::match_comms(&comms_of(&shell_identity)).is_none());
        let agent_identity = walk_identity_processes(&snap, 10, "pi");
        assert_eq!(
            agent_identity,
            vec![
                (10, "/bin/zsh".to_string()),
                (11, "/opt/homebrew/bin/pi".to_string())
            ]
        );
        assert_eq!(
            providers::match_comms(&comms_of(&agent_identity)).map(|e| e.id.as_str()),
            Some("pi")
        );
        assert_eq!(
            agent_identity
                .iter()
                .filter(|(_, comm)| providers::lookup(comm).is_some_and(|e| e.id == "pi"))
                .map(|(pid, _)| *pid)
                .collect::<Vec<_>>(),
            vec![11]
        );
    }
}
