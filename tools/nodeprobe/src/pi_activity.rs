use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(unix)]
use std::os::unix::net::UnixStream;

pub const CHANNEL_SCHEMA_VERSION: u32 = 2;
pub const ACTIVITY_WORKING: &str = "working";
pub const ACTIVITY_IDLE: &str = "idle";
pub const ACTIVITY_UNKNOWN: &str = "unknown";
pub const HEALTH_NORMAL: &str = "normal";
pub const HEALTH_ABNORMAL: &str = "abnormal";
pub const HEALTH_UNKNOWN: &str = "unknown";
pub const DEFAULT_MAX_AGE_MS: u64 = 5_000;

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct Record {
    pub schema_version: u32,
    pub provider: String,
    pub pid: i32,
    pub seat: String,
    pub activity: String,
    pub session_name: Option<String>,
    pub updated_at_ms: u64,
    pub socket_path: String,
    pub instance_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Observation {
    pub activity: &'static str,
    pub session_name: Option<String>,
    pub channel_available: bool,
    pub health: &'static str,
    pub detail: String,
}

pub fn channel_dir() -> Option<PathBuf> {
    std::env::var_os("NODEPROBE_PI_ACTIVITY_DIR").map(PathBuf::from)
}

pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn channel_path(dir: &Path, pid: i32) -> PathBuf {
    dir.join(format!("{pid}.json"))
}

fn validate(record: &Record, pid: i32, now_ms: u64, max_age_ms: u64) -> Result<(), String> {
    if record.schema_version != CHANNEL_SCHEMA_VERSION {
        return Err(format!(
            "unsupported channel schema_version={}",
            record.schema_version
        ));
    }
    if record.provider != "pi" {
        return Err(format!("provider={}", record.provider));
    }
    if record.pid != pid {
        return Err(format!("pid={}", record.pid));
    }
    if record.seat.is_empty() {
        return Err("empty seat".into());
    }
    if record.activity != ACTIVITY_WORKING && record.activity != ACTIVITY_IDLE {
        return Err(format!("activity={}", record.activity));
    }
    if record.updated_at_ms > now_ms.saturating_add(max_age_ms) {
        return Err("future timestamp".into());
    }
    if now_ms.saturating_sub(record.updated_at_ms) > max_age_ms {
        return Err("stale timestamp".into());
    }
    if record.socket_path.is_empty() || record.instance_id.is_empty() {
        return Err("missing process identity binding".into());
    }
    Ok(())
}

pub fn read_record(dir: &Path, pid: i32, now_ms: u64, max_age_ms: u64) -> Result<Record, String> {
    let path = channel_path(dir, pid);
    let text =
        std::fs::read_to_string(&path).map_err(|e| format!("read {}: {e}", path.display()))?;
    let record: Record =
        serde_json::from_str(&text).map_err(|e| format!("parse {}: {e}", path.display()))?;
    validate(&record, pid, now_ms, max_age_ms)?;
    Ok(record)
}

#[derive(Debug, Deserialize)]
struct ChallengeResponse {
    challenge: String,
    #[serde(flatten)]
    record: Record,
}

#[cfg(unix)]
fn verify_live_process(record: &Record, now_ms: u64, max_age_ms: u64) -> Result<Record, String> {
    let mut stream = UnixStream::connect(&record.socket_path)
        .map_err(|e| format!("connect {}: {e}", record.socket_path))?;
    stream
        .set_read_timeout(Some(std::time::Duration::from_millis(500)))
        .map_err(|e| format!("set channel timeout: {e}"))?;
    let challenge = format!("{}-{}-{}", record.pid, now_ms, record.instance_id);
    let request = serde_json::json!({"challenge": challenge});
    stream
        .write_all(format!("{request}\n").as_bytes())
        .map_err(|e| format!("write challenge: {e}"))?;
    let mut bytes = Vec::new();
    stream
        .read_to_end(&mut bytes)
        .map_err(|e| format!("read challenge response: {e}"))?;
    let response: ChallengeResponse =
        serde_json::from_slice(&bytes).map_err(|e| format!("parse challenge response: {e}"))?;
    if response.challenge != challenge || response.record.instance_id != record.instance_id {
        return Err("process identity challenge mismatch".into());
    }
    validate(&response.record, record.pid, now_ms, max_age_ms)?;
    Ok(response.record)
}

#[cfg(not(unix))]
fn verify_live_process(_record: &Record, _now_ms: u64, _max_age_ms: u64) -> Result<Record, String> {
    Err("process identity channel unsupported on this platform".into())
}

pub fn observe(dir: Option<&Path>, pi_pids: &[i32], now_ms: u64, max_age_ms: u64) -> Observation {
    if pi_pids.len() != 1 {
        return Observation {
            activity: ACTIVITY_UNKNOWN,
            session_name: None,
            channel_available: false,
            health: HEALTH_UNKNOWN,
            detail: if pi_pids.is_empty() {
                "pi activity channel unavailable: no unique Pi process".into()
            } else {
                format!(
                    "pi activity channel ambiguous: {} Pi processes",
                    pi_pids.len()
                )
            },
        };
    }
    let Some(dir) = dir else {
        return Observation {
            activity: ACTIVITY_UNKNOWN,
            session_name: None,
            channel_available: false,
            health: HEALTH_UNKNOWN,
            detail: "pi activity channel unavailable: NODEPROBE_PI_ACTIVITY_DIR unset".into(),
        };
    };
    match read_record(dir, pi_pids[0], now_ms, max_age_ms)
        .and_then(|record| verify_live_process(&record, now_ms, max_age_ms))
    {
        Ok(record) => Observation {
            activity: if record.activity == ACTIVITY_WORKING {
                ACTIVITY_WORKING
            } else {
                ACTIVITY_IDLE
            },
            session_name: record.session_name,
            channel_available: true,
            health: HEALTH_NORMAL,
            detail: format!(
                "pi activity channel pid={} seat={} activity={} source=live-ipc",
                record.pid, record.seat, record.activity
            ),
        },
        Err(error) if error.starts_with("read ") && error.contains("No such file") => Observation {
            activity: ACTIVITY_UNKNOWN,
            session_name: None,
            channel_available: false,
            health: HEALTH_UNKNOWN,
            detail: format!("pi activity channel missing: {error}"),
        },
        Err(error) => Observation {
            activity: ACTIVITY_UNKNOWN,
            session_name: None,
            channel_available: false,
            health: HEALTH_ABNORMAL,
            detail: format!("pi activity channel invalid: {error}"),
        },
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TitleName {
    Parsed(Option<String>),
    Ambiguous,
}

/// Parse only Pi's official `π - <cwd>` / `π - <session> - <cwd>` form.
/// A value containing another spaced delimiter is ambiguous and is rejected.
pub fn title_name(title: &str) -> Option<TitleName> {
    let parts: Vec<&str> = title.split(" - ").collect();
    if parts.first().copied() != Some("π") || parts.len() < 2 || parts.iter().any(|p| p.is_empty())
    {
        return None;
    }
    match parts.len() {
        2 => Some(TitleName::Parsed(None)),
        3 => Some(TitleName::Parsed(Some(parts[1].to_string()))),
        _ => Some(TitleName::Ambiguous),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    #[cfg(unix)]
    use std::io::BufRead;
    #[cfg(unix)]
    use std::os::unix::net::UnixListener;

    fn record(dir: &Path, pid: i32, activity: &str, updated_at_ms: u64) {
        let socket_path = dir.join(format!("{pid}-{updated_at_ms}.sock"));
        let r = Record {
            schema_version: CHANNEL_SCHEMA_VERSION,
            provider: "pi".into(),
            pid,
            seat: "seat-a".into(),
            activity: activity.into(),
            session_name: Some("build-main".into()),
            updated_at_ms,
            socket_path: socket_path.to_string_lossy().into_owned(),
            instance_id: format!("instance-{pid}-{updated_at_ms}"),
        };
        fs::create_dir_all(dir).unwrap();
        #[cfg(unix)]
        {
            let listener = UnixListener::bind(&socket_path).unwrap();
            listener.set_nonblocking(true).unwrap();
            let response_record = r.clone();
            std::thread::spawn(move || {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
                loop {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let mut line = String::new();
                            let _ = BufRead::read_line(
                                &mut std::io::BufReader::new(&mut stream),
                                &mut line,
                            );
                            let challenge = serde_json::from_str::<serde_json::Value>(&line)
                                .unwrap()["challenge"]
                                .clone();
                            let mut response = serde_json::to_value(response_record).unwrap();
                            response["challenge"] = challenge;
                            let _ = stream.write_all(format!("{response}\n").as_bytes());
                            break;
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            if std::time::Instant::now() >= deadline {
                                break;
                            }
                            std::thread::sleep(std::time::Duration::from_millis(1));
                        }
                        Err(_) => break,
                    }
                }
            });
        }
        fs::write(channel_path(dir, pid), serde_json::to_vec(&r).unwrap()).unwrap();
    }

    #[test]
    fn title_name_accepts_official_forms() {
        assert_eq!(title_name("π - repo"), Some(TitleName::Parsed(None)));
        assert_eq!(
            title_name("π - build-main - repo"),
            Some(TitleName::Parsed(Some("build-main".into())))
        );
    }

    #[test]
    fn title_name_rejects_spaced_hyphen_ambiguity() {
        assert_eq!(
            title_name("π - build - main - repo"),
            Some(TitleName::Ambiguous)
        );
        assert_eq!(title_name("π - repo - "), None);
        assert_eq!(title_name("pi - build - repo"), None);
    }

    #[test]
    fn valid_working_and_idle_channel_records_are_normal() {
        let dir = std::env::temp_dir().join(format!("nodeprobe-pi-channel-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        record(&dir, 41, ACTIVITY_WORKING, 1_000);
        let working = observe(Some(&dir), &[41], 1_001, DEFAULT_MAX_AGE_MS);
        assert_eq!(working.activity, ACTIVITY_WORKING);
        assert_eq!(working.health, HEALTH_NORMAL);
        record(&dir, 41, ACTIVITY_IDLE, 1_002);
        let idle = observe(Some(&dir), &[41], 1_003, DEFAULT_MAX_AGE_MS);
        assert_eq!(idle.activity, ACTIVITY_IDLE);
        assert_eq!(idle.health, HEALTH_NORMAL);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn stale_or_invalid_channel_never_becomes_idle() {
        let dir =
            std::env::temp_dir().join(format!("nodeprobe-pi-channel-stale-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        record(&dir, 42, ACTIVITY_IDLE, 1_000);
        let stale = observe(Some(&dir), &[42], 7_000, DEFAULT_MAX_AGE_MS);
        assert_eq!(stale.activity, ACTIVITY_UNKNOWN);
        assert_eq!(stale.health, HEALTH_ABNORMAL);
        fs::write(channel_path(&dir, 42), b"not-json").unwrap();
        let invalid = observe(Some(&dir), &[42], 1_001, DEFAULT_MAX_AGE_MS);
        assert_eq!(invalid.activity, ACTIVITY_UNKNOWN);
        assert_eq!(invalid.health, HEALTH_ABNORMAL);

        record(&dir, 42, ACTIVITY_WORKING, 2_000);
        let path = channel_path(&dir, 42);
        let mut reused: Record = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        reused.instance_id = "different-process-generation".into();
        fs::write(&path, serde_json::to_vec(&reused).unwrap()).unwrap();
        let pid_reused = observe(Some(&dir), &[42], 2_001, DEFAULT_MAX_AGE_MS);
        assert_eq!(pid_reused.activity, ACTIVITY_UNKNOWN);
        assert_eq!(pid_reused.health, HEALTH_ABNORMAL);
        assert!(pid_reused.detail.contains("challenge"));

        record(&dir, 46, ACTIVITY_IDLE, 3_000);
        let disconnected: Record =
            serde_json::from_slice(&fs::read(channel_path(&dir, 46)).unwrap()).unwrap();
        fs::remove_file(disconnected.socket_path).unwrap();
        let crashed = observe(Some(&dir), &[46], 3_001, DEFAULT_MAX_AGE_MS);
        assert_eq!(crashed.activity, ACTIVITY_UNKNOWN);
        assert_eq!(crashed.health, HEALTH_ABNORMAL);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn old_and_unknown_channel_versions_fail_closed() {
        let dir = std::env::temp_dir().join(format!(
            "nodeprobe-pi-channel-version-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        record(&dir, 47, ACTIVITY_WORKING, 4_000);
        let path = channel_path(&dir, 47);
        let mut old: Record = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        old.schema_version = 1;
        fs::write(&path, serde_json::to_vec(&old).unwrap()).unwrap();
        let old_observation = observe(Some(&dir), &[47], 4_001, DEFAULT_MAX_AGE_MS);
        assert_eq!(old_observation.activity, ACTIVITY_UNKNOWN);
        assert_eq!(old_observation.health, HEALTH_ABNORMAL);
        assert!(old_observation.detail.contains("unsupported"));

        record(&dir, 48, ACTIVITY_IDLE, 4_002);
        let path = channel_path(&dir, 48);
        let mut unknown: Record = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        unknown.schema_version = 99;
        fs::write(&path, serde_json::to_vec(&unknown).unwrap()).unwrap();
        let unknown_observation = observe(Some(&dir), &[48], 4_003, DEFAULT_MAX_AGE_MS);
        assert_eq!(unknown_observation.activity, ACTIVITY_UNKNOWN);
        assert_eq!(unknown_observation.health, HEALTH_ABNORMAL);
        assert!(unknown_observation.detail.contains("unsupported"));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn missing_and_multi_seat_are_fail_closed() {
        let dir = std::env::temp_dir().join(format!(
            "nodeprobe-pi-channel-missing-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        let missing = observe(Some(&dir), &[43], 1_000, DEFAULT_MAX_AGE_MS);
        assert_eq!(missing.activity, ACTIVITY_UNKNOWN);
        assert_eq!(missing.health, HEALTH_UNKNOWN);
        let multi = observe(Some(&dir), &[43, 44], 1_000, DEFAULT_MAX_AGE_MS);
        assert_eq!(multi.activity, ACTIVITY_UNKNOWN);
        assert_eq!(multi.health, HEALTH_UNKNOWN);
    }
}
