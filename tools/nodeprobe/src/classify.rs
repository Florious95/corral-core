//! pane_title classifiers aligned with contract 062 / Go detect.go.
//! State never falls back from unknown to idle.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Class {
    pub state: &'static str,
    pub provider: &'static str,
    pub first: Option<char>,
    pub known: bool,
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

const GROK_THINK: &str = " - Thinking - ";
const GROK_WAIT: &str = " - Waiting for response";
const GROK_IDLE_SUFFIX: &str = " - grok";

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
    fallback(title)
}

/// Dispatch the detector for a known provider. Unclaimed → unknown.
pub fn classify_for(provider: &str, title: &str) -> Class {
    let hit = match provider {
        PROVIDER_CLAUDE => claude_match(title),
        PROVIDER_GROK => grok_match(title),
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
    Class {
        state: STATE_UNKNOWN,
        provider: intern_expect(provider),
        first: first_non_space(title),
        known: false,
    }
}

pub fn classify_tsv_line(line: &str) -> Option<(String, Class, &'static str, &'static str)> {
    let line = line.trim_end_matches(['\n', '\r']);
    if line.is_empty() || line.starts_with('#') {
        return None;
    }
    let mut parts = line.splitn(3, '\t');
    let title = parts.next()?.to_string();
    let want_state = parts.next()?;
    let want_provider = parts.next()?;
    let got = if want_provider == PROVIDER_UNKNOWN {
        fallback(&title)
    } else {
        classify_for(want_provider, &title)
    };
    Some((
        title,
        got,
        intern_expect(want_state),
        intern_expect(want_provider),
    ))
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
        _ => STATE_UNKNOWN,
    }
}
