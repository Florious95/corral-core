use nodeprobe::{default_fixtures, error_report, probe, run_fixtures, SocketSpec};
use std::ffi::OsString;
use std::io::{self, Write};
use std::path::PathBuf;
use std::process::ExitCode;

fn usage() -> String {
    "nodeprobe -S <socket-path>\n\
     nodeprobe -L <socket-name>\n\
     nodeprobe fixtures [titles.tsv]\n"
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
