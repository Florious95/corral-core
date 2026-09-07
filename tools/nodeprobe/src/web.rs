use crate::{probe, Node, ProbeError, SocketSpec};
use serde::Serialize;
use std::collections::HashSet;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::Command;

pub const DEFAULT_BIND: &str = "127.0.0.1:8787";

#[derive(Debug, Clone)]
pub struct WebConfig {
    pub bind: String,
    pub socket_overrides: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct SocketSnapshot {
    pub socket: String,
    pub nodes: Vec<Node>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<ProbeError>,
}

#[derive(Debug, Serialize)]
pub struct WebSnapshot {
    pub schema_version: u32,
    pub sampled_at: String,
    pub sockets: Vec<SocketSnapshot>,
    pub nodes: Vec<Node>,
    pub errors: Vec<ProbeError>,
    pub unknown_count: usize,
}

pub fn current_uid_tmux_sockets() -> Result<Vec<PathBuf>, String> {
    let output = Command::new("id")
        .arg("-u")
        .output()
        .map_err(|e| format!("id -u spawn: {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "id -u failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    let uid = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if uid.is_empty() || !uid.chars().all(|c| c.is_ascii_digit()) {
        return Err("id -u returned malformed uid".into());
    }
    let mut found = Vec::new();
    for root in ["/private/tmp", "/tmp"] {
        let dir = Path::new(root).join(format!("tmux-{uid}"));
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries {
            let entry = entry.map_err(|e| format!("read {}: {e}", dir.display()))?;
            let ty = entry
                .file_type()
                .map_err(|e| format!("stat {}: {e}", entry.path().display()))?;
            #[cfg(unix)]
            if std::os::unix::fs::FileTypeExt::is_socket(&ty) {
                found.push(entry.path());
            }
        }
    }
    found.sort();
    found.dedup();
    Ok(found)
}

pub fn socket_specs(overrides: &[String]) -> Result<Vec<SocketSpec>, String> {
    let paths = if overrides.is_empty() {
        current_uid_tmux_sockets()?
            .into_iter()
            .map(|p| p.to_string_lossy().into_owned())
            .collect()
    } else {
        overrides.to_vec()
    };
    let mut seen = HashSet::new();
    let mut specs = Vec::new();
    for path in paths {
        let key = std::fs::canonicalize(&path)
            .unwrap_or_else(|_| PathBuf::from(&path))
            .to_string_lossy()
            .into_owned();
        if seen.insert(key) {
            specs.push(SocketSpec::Path(path));
        }
    }
    Ok(specs)
}

pub fn snapshot(specs: &[SocketSpec]) -> WebSnapshot {
    snapshot_with(specs, probe)
}

fn snapshot_with<F>(specs: &[SocketSpec], mut probe_fn: F) -> WebSnapshot
where
    F: FnMut(SocketSpec) -> Result<crate::Report, String>,
{
    let mut sockets = Vec::new();
    let mut nodes = Vec::new();
    let mut errors = Vec::new();
    let mut seen_panes = HashSet::new();
    let mut unknown_count = 0;
    for spec in specs {
        match probe_fn(spec.clone()) {
            Ok(report) => {
                let mut socket_nodes = Vec::new();
                if let Some(error) = report.error {
                    errors.push(ProbeError {
                        kind: format!("{}:{}", spec.display(), error.kind),
                        message: error.message.clone(),
                    });
                }
                for node in report.nodes {
                    let key = format!("{}\u{1f}{}", spec.display(), node.pane_id);
                    if !seen_panes.insert(key) {
                        continue;
                    }
                    if node.activity == "unknown" {
                        unknown_count += 1;
                    }
                    socket_nodes.push(node);
                }
                nodes.extend(socket_nodes.iter().cloned());
                sockets.push(SocketSnapshot {
                    socket: spec.display().to_string(),
                    nodes: socket_nodes,
                    errors: Vec::new(),
                });
            }
            Err(message) => {
                let error = ProbeError {
                    kind: "tmux_inventory".into(),
                    message,
                };
                errors.push(ProbeError {
                    kind: format!("{}:{}", spec.display(), error.kind),
                    message: error.message.clone(),
                });
                sockets.push(SocketSnapshot {
                    socket: spec.display().to_string(),
                    nodes: Vec::new(),
                    errors: vec![error],
                });
            }
        }
    }
    WebSnapshot {
        schema_version: crate::SCHEMA_VERSION,
        sampled_at: crate::sampled_at_for_web(),
        sockets,
        nodes,
        errors,
        unknown_count,
    }
}

pub fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

const INDEX_HTML: &str = r##"<!doctype html>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>nodeprobe</title><style>body{font:14px system-ui;margin:2rem;max-width:1100px}button{padding:.5rem 1rem}#status{margin:.8rem 0;color:#555}.unknown{color:#a60}.bad{color:#b00}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;padding:.35rem;text-align:left}</style>
<h1>nodeprobe</h1><button id="refresh">Refresh</button><span id="status">Loading…</span><div id="stats"></div><table><thead><tr><th>provider</th><th>activity</th><th>session</th><th>health</th><th>workspace</th><th>project</th><th>socket/session/pane/name</th><th>evidence</th></tr></thead><tbody id="rows"></tbody></table>
<script>
const $=id=>document.getElementById(id); const text=(el,v)=>{el.textContent=v??"—"};
function cell(row,value,cls){const c=document.createElement("td");if(cls)c.className=cls;text(c,value);row.append(c)}
async function refresh(){text($("status"),"Loading…");try{const r=await fetch("/api/status",{cache:"no-store"});if(!r.ok)throw Error("HTTP "+r.status);const d=await r.json();const body=$("rows");body.replaceChildren();for(const n of d.nodes){const row=document.createElement("tr");cell(row,n.provider);cell(row,n.activity,n.activity==="unknown"?"unknown":"");cell(row,n.session_name);cell(row,n.health,n.health!=="normal"?"unknown":"");cell(row,n.workspace_path);cell(row,n.project_name);cell(row,[n.socket,n.session,n.pane_id,n.name].filter(Boolean).join(" / "));cell(row,n.evidence?.detail);body.append(row)}text($("stats"),`${d.nodes.length} nodes · ${d.unknown_count} unknown · ${d.errors.length} errors`);text($("status"),"Updated "+d.sampled_at)}catch(e){text($("status"),"Error: "+e.message);text($("stats"),"unknown: status unavailable")}}
$("refresh").onclick=refresh;refresh();
</script>"##;

struct HttpResponse {
    status: &'static str,
    content_type: &'static str,
    body: Vec<u8>,
}

fn status_snapshot(result: Result<WebSnapshot, String>) -> WebSnapshot {
    match result {
        Ok(snapshot) => snapshot,
        Err(error) => WebSnapshot {
            schema_version: crate::SCHEMA_VERSION,
            sampled_at: crate::sampled_at_for_web(),
            sockets: Vec::new(),
            nodes: Vec::new(),
            errors: vec![ProbeError {
                kind: "socket_discovery".into(),
                message: error,
            }],
            unknown_count: 0,
        },
    }
}

fn route(path: &str, status: Option<Result<WebSnapshot, String>>) -> HttpResponse {
    match path {
        "/" => HttpResponse {
            status: "200 OK",
            content_type: "text/html; charset=utf-8",
            body: INDEX_HTML.as_bytes().to_vec(),
        },
        "/healthz" => HttpResponse {
            status: "200 OK",
            content_type: "application/json",
            body: br#"{"ok":true}"#.to_vec(),
        },
        "/api/status" => match serde_json::to_vec(&status_snapshot(
            status.expect("status result required for API route"),
        )) {
            Ok(body) => HttpResponse {
                status: "200 OK",
                content_type: "application/json",
                body,
            },
            Err(_) => HttpResponse {
                status: "500 Internal Server Error",
                content_type: "text/plain",
                body: b"serialization error".to_vec(),
            },
        },
        _ => HttpResponse {
            status: "404 Not Found",
            content_type: "text/plain; charset=utf-8",
            body: b"not found".to_vec(),
        },
    }
}

fn respond(mut stream: TcpStream, response: HttpResponse) {
    let header = format!(
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        response.status,
        response.content_type,
        response.body.len()
    );
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(&response.body);
}

fn handle(mut stream: TcpStream, config: &WebConfig) {
    let mut request = [0u8; 8192];
    let Ok(size) = stream.read(&mut request) else {
        return;
    };
    let line = String::from_utf8_lossy(&request[..size]);
    let Some(path) = line
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
    else {
        respond(
            stream,
            HttpResponse {
                status: "400 Bad Request",
                content_type: "text/plain; charset=utf-8",
                body: b"bad request".to_vec(),
            },
        );
        return;
    };
    let response = if path == "/api/status" {
        let result = socket_specs(&config.socket_overrides).map(|specs| snapshot(&specs));
        route(path, Some(result))
    } else {
        route(path, None)
    };
    respond(stream, response);
}

pub fn serve(config: WebConfig) -> Result<(), String> {
    let listener =
        TcpListener::bind(&config.bind).map_err(|e| format!("bind {}: {e}", config.bind))?;
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => handle(stream, &config),
            Err(error) => eprintln!("nodeprobe web connection: {error}"),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(socket: &str, pane_id: &str, activity: &str) -> Node {
        Node {
            socket: socket.into(),
            workspace_path: "/tmp/project".into(),
            project_name: "project".into(),
            session: "session".into(),
            window_index: 0,
            window_name: "window".into(),
            pane_id: pane_id.into(),
            name: "name<script>".into(),
            provider: "pi".into(),
            state: activity.into(),
            activity: activity.into(),
            session_name: Some("build-main".into()),
            health: "normal".into(),
            background_tasks: crate::classify::BackgroundTasks::Unknown,
            footer_error: None,
            evidence: crate::Evidence {
                method: "test".into(),
                detail: "<untrusted>".into(),
                glyph: None,
                codepoint: None,
                title: None,
                comms: vec!["pi".into()],
            },
        }
    }

    fn report(socket: &str, nodes: Vec<Node>) -> crate::Report {
        crate::Report {
            schema_version: crate::SCHEMA_VERSION,
            socket: socket.into(),
            sampled_at: "2026-01-01T00:00:00Z".into(),
            nodes,
            error: None,
        }
    }

    #[test]
    fn html_escape_handles_untrusted_text() {
        assert_eq!(
            html_escape("<x a=\"b\">&'"),
            "&lt;x a=&quot;b&quot;&gt;&amp;&#39;"
        );
    }

    #[test]
    fn route_responses_expose_api_healthz_and_refresh_page() {
        let snapshot = WebSnapshot {
            schema_version: crate::SCHEMA_VERSION,
            sampled_at: "2026-01-01T00:00:00Z".into(),
            sockets: Vec::new(),
            nodes: vec![node("/tmp/pi", "%1", "unknown")],
            errors: Vec::new(),
            unknown_count: 1,
        };
        let response = route("/api/status", Some(Ok(snapshot)));
        assert_eq!(response.status, "200 OK");
        assert_eq!(response.content_type, "application/json");
        let json: serde_json::Value = serde_json::from_slice(&response.body).unwrap();
        assert_eq!(json["schema_version"], crate::SCHEMA_VERSION);
        assert_eq!(json["unknown_count"], 1);
        assert_eq!(route("/healthz", None).body, br#"{"ok":true}"#);

        let page = route("/", None);
        let page = String::from_utf8(page.body).unwrap();
        assert!(page.contains("id=\"refresh\""));
        assert!(page.contains("fetch(\"/api/status\""));
        assert!(page.contains("textContent"));
        assert!(!page.contains("name<script>"));
    }

    #[test]
    fn overrides_are_deduplicated() {
        let specs = socket_specs(&["/tmp/a".into(), "/tmp/a".into()]).unwrap();
        assert_eq!(specs.len(), 1);
    }

    #[test]
    fn injected_multi_socket_snapshot_deduplicates_and_keeps_failures() {
        let specs = vec![
            SocketSpec::Path("/good".into()),
            SocketSpec::Path("/good".into()),
            SocketSpec::Path("/bad".into()),
        ];
        let snapshot = snapshot_with(&specs, |spec| match spec.display() {
            "/good" => Ok(report("/good", vec![node("/good", "%1", "unknown")])),
            "/bad" => Err("tmux unavailable".into()),
            _ => unreachable!(),
        });
        assert_eq!(snapshot.nodes.len(), 1);
        assert_eq!(snapshot.unknown_count, 1);
        assert_eq!(snapshot.sockets.len(), 3);
        assert_eq!(snapshot.errors.len(), 1);
        assert_eq!(snapshot.sockets[2].errors.len(), 1);
        assert!(snapshot.errors[0].kind.contains("/bad"));
    }
}
