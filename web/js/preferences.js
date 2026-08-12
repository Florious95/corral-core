/*
 * AgentMirror web client — persisted pairing configuration and theme preference.
 * Storage access is isolated here so callers receive stable fallbacks when localStorage is unavailable.
 */

const CONFIG_KEY = 'agentmirror:config';
const THEME_KEY = 'agentmirror:theme';
export const THEMES = Object.freeze(['system', 'dark', 'light']);

/**
 * Loads the saved URL/token pair used for refresh recovery.
 * @contract
 * @pre storage implements getItem
 * @post returns a string URL/token pair or null for absent/invalid data
 * @err storage and JSON errors are folded into null
 * @inv never logs or partially returns the token
 */
export function loadConfig(storage = globalThis.localStorage) {
  try {
    const value = JSON.parse(storage.getItem(CONFIG_KEY) || 'null');
    if (!value || typeof value.url !== 'string' || typeof value.token !== 'string') return null;
    return { url: value.url, token: value.token };
  } catch { return null; }
}

/**
 * Persists the URL/token pair used for refresh recovery.
 * @contract
 * @pre url and token are strings and storage implements setItem
 * @post storage contains one JSON object with the supplied URL/token
 * @err storage serialization/write errors propagate
 * @inv token is written only to the configured storage key
 */
export function saveConfig(url, token, storage = globalThis.localStorage) {
  storage.setItem(CONFIG_KEY, JSON.stringify({ url, token }));
}

/**
 * Removes the persisted pairing configuration.
 * @contract
 * @pre storage may implement removeItem
 * @post the configuration key is absent when storage permits removal
 * @err storage errors are ignored
 * @inv theme preference is unchanged
 */
export function clearConfig(storage = globalThis.localStorage) {
  try { storage.removeItem(CONFIG_KEY); } catch { /* unavailable storage */ }
}

/**
 * Loads a validated theme preference.
 * @contract
 * @pre storage may implement getItem
 * @post returns system, dark, or light; absent/invalid values become system
 * @err storage errors are folded into system
 * @inv does not mutate storage
 */
export function loadTheme(storage = globalThis.localStorage) {
  try {
    const value = storage.getItem(THEME_KEY) || 'system';
    return THEMES.includes(value) ? value : 'system';
  } catch { return 'system'; }
}

/**
 * Persists one supported theme preference.
 * @contract
 * @pre theme belongs to THEMES and storage implements setItem
 * @post storage contains the supplied theme
 * @err throws for unsupported themes or storage write failures
 * @inv pairing configuration is unchanged
 */
export function saveTheme(theme, storage = globalThis.localStorage) {
  if (!THEMES.includes(theme)) throw new Error(`unsupported theme: ${theme}`);
  storage.setItem(THEME_KEY, theme);
}

/**
 * Resolves the system theme choice to a concrete palette.
 * @contract
 * @pre theme is system, dark, or light; prefersDark is truthy or falsy
 * @post returns dark/light for system, otherwise returns theme unchanged
 * @err none
 * @inv pure function with no storage or DOM effects
 */
export function resolvedTheme(theme, prefersDark) {
  return theme === 'system' ? (prefersDark ? 'dark' : 'light') : theme;
}
