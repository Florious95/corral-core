//! ---
//! purpose: provider 匹配器——标题三态 + 页脚后台任务维；core 不认具体 UI 文案
//! contract:
//!   provides:
//!     - name: classify_for
//!       what: 按已识别 provider 把标题判成 working/idle/unknown，unknown 不折 idle
//!     - name: background_tasks_for
//!       what: 按语料里该 provider 的 footer 规则返回 Count(N) 或 Unknown；无规则绝不报 0
//!   depends:
//!     - fixtures/titles.tsv（kind=title|footer，与 Go l2detect 共读）
//! boundary:
//!   - 不把 background_tasks 折进 state
//!   - 具体页脚短语只存在语料，不写进本文件字符串
//!   - 加新 provider 只加语料行（同一种匹配）或本文件一家一个 matcher，不改 lib.rs 聚合
//! maturity: wired
//! ---
//! State never falls back from unknown to idle.

use std::collections::HashMap;
use std::path::Path;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Class {
    pub state: &'static str,
    pub provider: &'static str,
    pub first: Option<char>,
    pub known: bool,
}

/// 观测维：有规则且未命中=0；有规则且命中=N；语料无该 provider 的 footer 行=Unknown。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BackgroundTasks {
    Count(u32),
    Unknown,
}

impl serde::Serialize for BackgroundTasks {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Self::Count(n) => serializer.serialize_u32(*n),
            Self::Unknown => serializer.serialize_str("unknown"),
        }
    }
}

pub const STATE_WORKING: &str = "working";
pub const STATE_IDLE: &str = "idle";
pub const STATE_UNKNOWN: &str = "unknown";
pub const PROVIDER_UNKNOWN: &str = "unknown";
pub const PROVIDER_GROK: &str = "grok";
pub const PROVIDER_CLAUDE: &str = "claude_code";
pub const PROVIDER_CODEX: &str = "codex";
pub const PROVIDER_COPILOT: &str = "copilot";
pub const PROVIDER_CURSOR: &str = "cursor";
pub const PROVIDER_PI: &str = "pi";

const GROK_THINK: &str = " - Thinking - ";
const GROK_WAIT: &str = " - Waiting for response";
const GROK_IDLE_SUFFIX: &str = " - grok";
const CODEX_SPINNER: [char; 10] = [
    '\u{280B}', '\u{2819}', '\u{2839}', '\u{2838}', '\u{283C}', '\u{2834}', '\u{2826}', '\u{2827}',
    '\u{2807}', '\u{280F}',
];

pub fn first_non_space(title: &str) -> Option<char> {
    title.chars().find(|c| !c.is_whitespace())
}

pub fn leading_glyph(c: char) -> bool {
    // Same split as Go unicode.IsLetter / IsNumber: letter or number is
    // not a leading glyph; everything else (symbols, punctuation) is.
    !c.is_alphabetic() && !c.is_numeric()
}

pub fn format_codepoint(c: char) -> String {
    format!("U+{:04X}", c as u32)
}

fn claude_match(title: &str) -> Option<&'static str> {
    let c = first_non_space(title)?;
    match c {
        '\u{25D0}' | '\u{25D3}' | '\u{25D1}' | '\u{25D2}' => Some(STATE_WORKING),
        '\u{2733}' => Some(STATE_IDLE),
        _ => None,
    }
}

fn grok_match(title: &str) -> Option<&'static str> {
    if let Some(c) = first_non_space(title) {
        if ('\u{2800}'..='\u{28FF}').contains(&c) {
            return Some(STATE_WORKING);
        }
    }
    if title.contains(GROK_THINK) || title.contains(GROK_WAIT) {
        return Some(STATE_WORKING);
    }
    if title.ends_with(GROK_IDLE_SUFFIX) {
        match first_non_space(title) {
            None => return Some(STATE_IDLE),
            Some(c) if !leading_glyph(c) => return Some(STATE_IDLE),
            Some(_) => {}
        }
    }
    None
}

fn codex_match(title: &str) -> Option<&'static str> {
    let c = first_non_space(title)?;
    CODEX_SPINNER.contains(&c).then_some(STATE_WORKING)
}

fn fallback(title: &str) -> Class {
    match first_non_space(title) {
        None => Class {
            state: STATE_IDLE,
            provider: PROVIDER_UNKNOWN,
            first: None,
            known: true,
        },
        Some(c) if !leading_glyph(c) => Class {
            state: STATE_IDLE,
            provider: PROVIDER_UNKNOWN,
            first: Some(c),
            known: true,
        },
        Some(c) => Class {
            state: STATE_UNKNOWN,
            provider: PROVIDER_UNKNOWN,
            first: Some(c),
            known: false,
        },
    }
}

/// Title-only classify (fixture corpus). Live nodes use classify_for after
/// comm identity so detectors never compete on one title.
pub fn classify(title: &str) -> Class {
    // After a Pi process exits, its strict official title is the only safe
    // provider fallback. Ambiguous/malformed π titles remain unclaimed.
    if matches!(
        crate::pi_activity::title_name(title),
        Some(crate::pi_activity::TitleName::Parsed(_))
    ) {
        return Class {
            state: STATE_UNKNOWN,
            provider: PROVIDER_PI,
            first: first_non_space(title),
            known: false,
        };
    }
    fallback(title)
}

/// Dispatch the detector for a known provider, then fall back to the shared
/// three-state rule (062).
///
/// 🔴 068 §8 修正：原先「本家不认领 ⇒ unknown」盖掉了 062，是回归——
/// 刚起的会话标题是光秃秃的产品名，本家检测器不认领，于是每个新会话都先判「未知」。
/// 正确语义：无前导符号（字母/数字/空）⇒ 空闲；unknown 只由**认不出的前导符号**产生。
pub fn classify_for(provider: &str, title: &str) -> Class {
    // Pi currently keeps a static `π` identity title across idle and active
    // turns.  Title-only observation therefore has no honest state signal.
    if provider == PROVIDER_PI {
        return Class {
            state: STATE_UNKNOWN,
            provider: PROVIDER_PI,
            first: first_non_space(title),
            known: false,
        };
    }
    let hit = match provider {
        PROVIDER_CLAUDE => claude_match(title),
        PROVIDER_GROK => grok_match(title),
        PROVIDER_CODEX => codex_match(title),
        _ => None,
    };
    if let Some(state) = hit {
        return Class {
            state,
            provider: intern_expect(provider),
            first: first_non_space(title),
            known: true,
        };
    }
    // 不认领 ⇒ 落回三态；provider 仍记本家（身份优先不变）
    let fb = fallback(title);
    Class {
        state: fb.state,
        provider: intern_expect(provider),
        first: fb.first,
        known: fb.known,
    }
}

pub const KIND_TITLE: &str = "title";
pub const KIND_FOOTER: &str = "footer";

#[derive(Debug)]
pub enum CorpusRow {
    Title {
        payload: String,
        got: Class,
        want_state: &'static str,
        want_provider: &'static str,
    },
    Footer {
        payload: String,
        got: BackgroundTasks,
        want: BackgroundTasks,
        provider: String,
    },
}

/// Four-column corpus: payload, want, provider, kind. Missing kind is an error.
pub fn parse_corpus_line(line: &str) -> Result<Option<CorpusRow>, String> {
    let line = line.trim_end_matches(['\n', '\r']);
    if line.is_empty() || line.starts_with('#') {
        return Ok(None);
    }
    let parts: Vec<&str> = line.split('\t').collect();
    if parts.len() != 4 {
        return Err(format!(
            "need payload<TAB>want<TAB>provider<TAB>kind (4 fields), got {}",
            parts.len()
        ));
    }
    let (payload, want, provider, kind) = (parts[0], parts[1], parts[2], parts[3]);
    match kind {
        KIND_TITLE => {
            let got = if provider == PROVIDER_UNKNOWN {
                fallback(payload)
            } else {
                classify_for(provider, payload)
            };
            Ok(Some(CorpusRow::Title {
                payload: payload.to_string(),
                got,
                want_state: intern_expect(want),
                want_provider: intern_expect(provider),
            }))
        }
        KIND_FOOTER => {
            let got = background_tasks_for(provider, payload);
            let want_bg = parse_bg_want(want)?;
            Ok(Some(CorpusRow::Footer {
                payload: payload.to_string(),
                got,
                want: want_bg,
                provider: provider.to_string(),
            }))
        }
        other => Err(format!("unknown kind {other:?} (want title|footer)")),
    }
}

/// Back-compat for title-only callers. Footer rows and malformed lines are skipped.
pub fn classify_tsv_line(line: &str) -> Option<(String, Class, &'static str, &'static str)> {
    match parse_corpus_line(line) {
        Ok(Some(CorpusRow::Title {
            payload,
            got,
            want_state,
            want_provider,
        })) => Some((payload, got, want_state, want_provider)),
        _ => None,
    }
}

fn parse_bg_want(want: &str) -> Result<BackgroundTasks, String> {
    if want == "unknown" {
        return Ok(BackgroundTasks::Unknown);
    }
    want.parse::<u32>()
        .map(BackgroundTasks::Count)
        .map_err(|_| format!("footer want must be 0|N|unknown, got {want:?}"))
}

struct FooterRules {
    needles: Vec<String>,
}

static FOOTER_RULES: OnceLock<HashMap<String, FooterRules>> = OnceLock::new();

fn footer_rules() -> &'static HashMap<String, FooterRules> {
    FOOTER_RULES.get_or_init(|| load_footer_rules(&default_titles_tsv()))
}

pub fn default_titles_tsv() -> std::path::PathBuf {
    std::env::var_os("NODEPROBE_FIXTURES")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/titles.tsv"))
}

fn load_footer_rules(path: &Path) -> HashMap<String, FooterRules> {
    let mut map: HashMap<String, FooterRules> = HashMap::new();
    let Ok(text) = std::fs::read_to_string(path) else {
        return map;
    };
    for line in text.lines() {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split('\t').collect();
        if parts.len() != 4 || parts[3] != KIND_FOOTER {
            continue;
        }
        let (payload, want, provider) = (parts[0], parts[1], parts[2]);
        // want=unknown is a corpus assertion (no rule), not a rule row.
        // Registering it would make missing-rule look like Count(0).
        if want == "unknown" {
            continue;
        }
        let entry = map.entry(provider.to_string()).or_insert(FooterRules {
            needles: Vec::new(),
        });
        if let Ok(n) = want.parse::<u32>() {
            if n >= 1 {
                entry.needles.push(payload.to_string());
            }
        }
    }
    map
}

/// Provider matcher. Core must call this and not name any UI phrase.
pub fn background_tasks_for(provider: &str, footer: &str) -> BackgroundTasks {
    let rules = footer_rules();
    let Some(r) = rules.get(provider) else {
        return BackgroundTasks::Unknown;
    };
    for needle in &r.needles {
        if footer.contains(needle.as_str()) {
            return BackgroundTasks::Count(count_before_needle(footer, needle));
        }
    }
    BackgroundTasks::Count(0)
}

fn count_before_needle(footer: &str, needle: &str) -> u32 {
    let Some(idx) = footer.find(needle) else {
        return 1;
    };
    let before = &footer[..idx];
    let digits: String = before
        .chars()
        .rev()
        .skip_while(|c| !c.is_ascii_digit())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    if digits.is_empty() {
        return 1;
    }
    digits
        .chars()
        .rev()
        .collect::<String>()
        .parse()
        .unwrap_or(1)
}

fn intern_expect(s: &str) -> &'static str {
    match s {
        "working" => STATE_WORKING,
        "idle" => STATE_IDLE,
        "unknown" => STATE_UNKNOWN,
        "grok" => PROVIDER_GROK,
        "claude_code" => PROVIDER_CLAUDE,
        "codex" => PROVIDER_CODEX,
        "copilot" => PROVIDER_COPILOT,
        "cursor" => PROVIDER_CURSOR,
        "pi" => PROVIDER_PI,
        _ => STATE_UNKNOWN,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn footer_grok_running_is_count_state_stays_idle() {
        let title = "修滚动摘要 - grok";
        let class = classify_for(PROVIDER_GROK, title);
        assert_eq!(class.state, STATE_IDLE);
        let footer = "◉ 1 command still running · 1 queued — Enter to send now";
        let bg = background_tasks_for(PROVIDER_GROK, footer);
        assert_eq!(bg, BackgroundTasks::Count(1));
        assert_ne!(class.state, STATE_WORKING, "must not fold bg into state");
    }

    #[test]
    fn footer_grok_vacuum_is_zero_not_constant_one() {
        let bg = background_tasks_for(PROVIDER_GROK, "just a prompt · nothing in flight");
        assert_eq!(bg, BackgroundTasks::Count(0));
    }

    #[test]
    fn footer_provider_without_rule_is_unknown_not_zero() {
        let grok_footer = "◉ 1 command still running · send a message to interrupt";
        assert_eq!(
            background_tasks_for(PROVIDER_CLAUDE, grok_footer),
            BackgroundTasks::Unknown
        );
        assert_eq!(
            background_tasks_for(PROVIDER_CODEX, grok_footer),
            BackgroundTasks::Unknown
        );
        assert_eq!(
            background_tasks_for(PROVIDER_CURSOR, grok_footer),
            BackgroundTasks::Unknown
        );
        assert_ne!(
            background_tasks_for(PROVIDER_CLAUDE, grok_footer),
            BackgroundTasks::Count(0)
        );
    }

    #[test]
    fn unknown_title_state_is_not_idle() {
        let got = fallback("※probe-unknown-full-title");
        assert_eq!(got.state, STATE_UNKNOWN);
        assert_ne!(got.state, STATE_IDLE);
        assert!(got.first.is_some());
    }

    #[test]
    fn pi_official_title_is_provider_fallback_but_activity_unknown() {
        let class = classify("π - build-main - repo");
        assert_eq!(class.provider, PROVIDER_PI);
        assert_eq!(class.state, STATE_UNKNOWN);
        assert!(!class.known);
        assert_eq!(
            classify("π - build - main - repo").provider,
            PROVIDER_UNKNOWN
        );
    }

    #[test]
    fn pi_observed_title_is_unknown_not_idle() {
        let title = "π - pi-activity-sample-subject-luna - 多agent协作";
        let got = classify_for(PROVIDER_PI, title);
        assert_eq!(got.state, STATE_UNKNOWN);
        assert_eq!(got.provider, PROVIDER_PI);
        assert_eq!(got.first, Some('π'));
        assert!(!got.known);
    }

    #[test]
    fn pi_identical_active_and_idle_labels_have_no_title_signal() {
        let title = "π - pi-activity-sample-subject-luna - 多agent协作";
        for (label, observed_title) in [("active", title), ("idle", title)] {
            let got = classify_for(PROVIDER_PI, observed_title);
            assert_eq!(got.state, STATE_UNKNOWN, "label={label}");
            assert_eq!(got.provider, PROVIDER_PI, "label={label}");
        }
    }

    #[test]
    fn pi_unrecognized_glyph_remains_unknown() {
        let got = classify_for(PROVIDER_PI, "※ Pi task");
        assert_eq!(got.state, STATE_UNKNOWN);
        assert_eq!(got.provider, PROVIDER_PI);
        assert_eq!(got.first, Some('※'));
        assert!(!got.known);
    }

    #[test]
    fn codex_spinner_frames_are_working() {
        for frame in CODEX_SPINNER {
            let title = format!("{frame} Codex task");
            let got = classify_for(PROVIDER_CODEX, &title);
            assert_eq!(got.state, STATE_WORKING, "title={title:?}");
            assert_eq!(got.provider, PROVIDER_CODEX);
        }
    }

    #[test]
    fn codex_plain_title_is_idle() {
        let got = classify_for(PROVIDER_CODEX, "Codex CLI");
        assert_eq!(got.state, STATE_IDLE);
        assert_eq!(got.provider, PROVIDER_CODEX);
    }

    #[test]
    fn codex_unknown_braille_is_unknown() {
        let got = classify_for(PROVIDER_CODEX, "⠁ Codex task");
        assert_eq!(got.state, STATE_UNKNOWN);
        assert_eq!(got.first, Some('⠁'));
        assert_eq!(got.provider, PROVIDER_CODEX);
    }
}
