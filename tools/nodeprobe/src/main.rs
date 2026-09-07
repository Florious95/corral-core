use nodeprobe::{default_fixtures, error_report, probe, run_fixtures, web, SocketSpec};
use std::ffi::OsString;
use std::io::{self, Write};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::process::ExitCode;

fn usage() -> String {
    "nodeprobe -S <socket-path>\n\
     nodeprobe -L <socket-name>\n\
     nodeprobe fixtures [titles.tsv]\n\
     nodeprobe web [--bind 127.0.0.1:8787] [-S <socket-path>]...\n"
        .into()
}

fn main() -> ExitCode {
    let argv: Vec<OsString> = std::env::args_os().collect();
    let words: Vec<String> = argv
        .iter()
        .skip(1)
        .map(|s| s.to_string_lossy().into_owned())
        .collect();

    if words.is_empty() || words.iter().any(|w| w == "-h" || w == "--help") {
        let _ = io::stderr().write_all(usage().as_bytes());
        return if words.is_empty() {
            ExitCode::from(2)
        } else {
            ExitCode::SUCCESS
        };
    }

    if words[0] == "fixtures" {
        let path = if words.len() >= 2 {
            PathBuf::from(&words[1])
        } else {
            default_fixtures()
        };
        match run_fixtures(&path) {
            Ok(text) => {
                let _ = io::stdout().write_all(text.as_bytes());
                return ExitCode::SUCCESS;
            }
            Err(e) => {
                let _ = writeln!(io::stderr(), "nodeprobe fixtures: {e}");
                return ExitCode::from(1);
            }
        }
    }

    if words[0] == "web" {
        let config = match parse_web(&words[1..]) {
            Ok(config) => config,
            Err(e) => {
                let _ = writeln!(io::stderr(), "{e}\n{}", usage());
                return ExitCode::from(2);
            }
        };
        return match web::serve(config) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                let _ = writeln!(io::stderr(), "nodeprobe web: {error}");
                ExitCode::from(1)
            }
        };
    }

    let spec = match parse_socket(&words) {
        Ok(s) => s,
        Err(e) => {
            let _ = writeln!(io::stderr(), "{e}\n{}", usage());
            return ExitCode::from(2);
        }
    };

    match probe(spec.clone()) {
        Ok(report) => match serde_json::to_string_pretty(&report) {
            Ok(s) => {
                let _ = writeln!(io::stdout(), "{s}");
                ExitCode::SUCCESS
            }
            Err(e) => {
                let _ = writeln!(io::stderr(), "json: {e}");
                ExitCode::from(1)
            }
        },
        Err(e) => {
            // Preserve a machine-readable error and non-zero status. An
            // inventory failure is never represented as an empty success.
            match serde_json::to_string_pretty(&error_report(&spec, e)) {
                Ok(s) => {
                    let _ = writeln!(io::stdout(), "{s}");
                }
                Err(json_err) => {
                    let _ = writeln!(io::stderr(), "nodeprobe: {json_err}");
                }
            }
            ExitCode::from(1)
        }
    }
}

fn parse_web(words: &[String]) -> Result<web::WebConfig, String> {
    let mut bind = web::DEFAULT_BIND.to_string();
    let mut sockets = Vec::new();
    let mut i = 0usize;
    while i < words.len() {
        match words[i].as_str() {
            "--bind" => {
                bind = words.get(i + 1).ok_or("missing value for --bind")?.clone();
                i += 2;
            }
            "-S" | "--socket" => {
                sockets.push(words.get(i + 1).ok_or("missing socket value")?.clone());
                i += 2;
            }
            other => return Err(format!("unknown web flag {other}")),
        }
    }
    if !is_local_bind(&bind) {
        return Err("web bind must be localhost".into());
    }
    Ok(web::WebConfig {
        bind,
        socket_overrides: sockets,
    })
}

fn is_local_bind(bind: &str) -> bool {
    bind.strip_prefix("localhost:")
        .is_some_and(|port| !port.is_empty())
        || bind
            .parse::<SocketAddr>()
            .is_ok_and(|address| address.ip().is_loopback())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn web_bind_is_localhost_and_flags_parse() {
        let config = parse_web(&[]).unwrap();
        assert_eq!(config.bind, web::DEFAULT_BIND);
        let config = parse_web(&[
            "--bind".into(),
            "127.0.0.1:9000".into(),
            "-S".into(),
            "/tmp/a".into(),
            "--socket".into(),
            "/tmp/b".into(),
        ])
        .unwrap();
        assert_eq!(config.socket_overrides, ["/tmp/a", "/tmp/b"]);
        assert!(parse_web(&["--bind".into(), "0.0.0.0:9000".into()]).is_err());
        assert!(parse_web(&["--bind".into(), "localhost:9000".into()]).is_ok());
    }
}

fn parse_socket(words: &[String]) -> Result<SocketSpec, String> {
    let mut i = 0usize;
    let mut spec = None;
    while i < words.len() {
        match words[i].as_str() {
            "-S" => {
                let v = words.get(i + 1).ok_or("missing value for -S")?;
                spec = Some(SocketSpec::Path(v.clone()));
                i += 2;
            }
            "-L" => {
                let v = words.get(i + 1).ok_or("missing value for -L")?;
                spec = Some(SocketSpec::Name(v.clone()));
                i += 2;
            }
            other => return Err(format!("unknown flag {other}")),
        }
    }
    spec.ok_or_else(|| "need -S <path> or -L <name>".into())
}
