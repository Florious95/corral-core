/** DesignTokens 语义色板 —— 数值与 app DesignTokens.kt DarkPalette / LightPalette 对齐。 */

import type { ThemeName } from './types'

export type { ThemeName }

export interface AppPalette {
  screenBackground: string
  listBackground: string
  cardBackground: string
  cardBorder: string
  divider: string
  dividerStrong: string
  rowPressed: string
  titleText: string
  rowTitleText: string
  pathText: string
  metaText: string
  bodyText: string
  accent: string
  accentContainer: string
  accentContainerPressed: string
  onAccent: string
  starOn: string
  starOff: string
  busyChipBg: string
  busyChipText: string
  busyDot: string
  idleChipBg: string
  idleChipText: string
  unknownChipBg: string
  unknownChipText: string
  unknownDot: string
  statusPillBg: string
  statusPillText: string
  navBackground: string
  navRail: string
  navActive: string
  navInactive: string
  consoleBackground: string
  keycapBackground: string
  keycapBorder: string
  keycapTopHighlight: string
  keycapText: string
  keycapPressed: string
  keycapDangerBorder: string
  keycapDangerTopHighlight: string
  keycapDangerText: string
  keycapDangerPressed: string
  arrowClusterTrack: string
  inputBackground: string
  inputBorder: string
  inputText: string
  inputPlaceholder: string
  promptGlyph: string
  sendEnabledBg: string
  sendEnabledFg: string
  sendDisabledBg: string
  sendDisabledFg: string
  scrim: string
  sheetBackground: string
  sheetSurface: string
  sheetGrabber: string
  sheetRowPressed: string
  sheetCurrentRowBg: string
  sheetCurrentRail: string
  currentBadgeText: string
  currentBadgeBorder: string
  segmentedTrack: string
  segmentedSelectedBg: string
  segmentedSelectedText: string
  segmentedText: string
  chipBg: string
  chipText: string
  chipPressed: string
  chipSelectedBg: string
  chipSelectedText: string
  outlineButtonBorder: string
  outlineButtonText: string
  outlineButtonPressed: string
  termBackground: string
  termForeground: string
  termMuted: string
}

export const DarkPalette: AppPalette = {
  screenBackground: '#070B14',
  listBackground: '#0D1422',
  cardBackground: '#0F1725',
  cardBorder: 'rgba(120, 160, 255, 0.10)',
  divider: 'rgba(120, 160, 255, 0.08)',
  dividerStrong: 'rgba(120, 160, 255, 0.16)',
  rowPressed: '#131C2E',
  titleText: '#EAF0FA',
  rowTitleText: '#E4EBF7',
  pathText: '#6E82A4',
  metaText: '#7286A8',
  bodyText: '#8497B8',
  accent: '#77A6FF',
  accentContainer: 'rgba(119, 166, 255, 0.14)',
  accentContainerPressed: 'rgba(119, 166, 255, 0.24)',
  onAccent: '#00183D',
  starOn: '#77A6FF',
  starOff: 'rgba(196, 208, 230, 0.30)',
  busyChipBg: 'rgba(79, 209, 192, 0.15)',
  busyChipText: '#5FDCC9',
  busyDot: '#4FD1C0',
  idleChipBg: 'rgba(120, 160, 255, 0.09)',
  idleChipText: '#8497B8',
  unknownChipBg: 'rgba(240, 135, 159, 0.15)',
  unknownChipText: '#F0879F',
  unknownDot: '#F0879F',
  statusPillBg: 'rgba(79, 209, 192, 0.14)',
  statusPillText: '#4FD1C0',
  navBackground: '#070B14',
  navRail: '#77A6FF',
  navActive: '#9CC0FF',
  navInactive: '#8497B8',
  consoleBackground: '#0E1421',
  keycapBackground: '#1A2233',
  keycapBorder: 'rgba(120, 160, 255, 0.16)',
  keycapTopHighlight: 'rgba(160, 190, 255, 0.09)',
  keycapText: '#C4D0E6',
  keycapPressed: '#232D42',
  keycapDangerBorder: 'rgba(232, 112, 154, 0.32)',
  keycapDangerTopHighlight: 'rgba(240, 135, 159, 0.10)',
  keycapDangerText: '#F0879F',
  keycapDangerPressed: '#2E2029',
  arrowClusterTrack: 'rgba(120, 160, 255, 0.07)',
  inputBackground: '#131A2A',
  inputBorder: 'rgba(120, 160, 255, 0.16)',
  inputText: '#E4EBF7',
  inputPlaceholder: '#7286A8',
  promptGlyph: '#4FD1C0',
  sendEnabledBg: '#77A6FF', // same as accent — one filled primary only
  sendEnabledFg: '#FFFFFF',
  sendDisabledBg: 'rgba(120, 160, 255, 0.09)',
  sendDisabledFg: 'rgba(196, 208, 230, 0.30)',
  scrim: 'rgba(3, 6, 12, 0.62)',
  sheetBackground: '#0F1725',
  sheetSurface: '#0D1422',
  sheetGrabber: 'rgba(180, 205, 255, 0.26)',
  sheetRowPressed: '#162034',
  sheetCurrentRowBg: 'rgba(119, 166, 255, 0.07)',
  sheetCurrentRail: '#77A6FF',
  currentBadgeText: '#9CC0FF',
  currentBadgeBorder: 'rgba(119, 166, 255, 0.30)',
  segmentedTrack: 'rgba(120, 160, 255, 0.08)',
  segmentedSelectedBg: '#1D2740',
  segmentedSelectedText: '#9CC0FF',
  segmentedText: '#95A8C8',
  chipBg: 'rgba(120, 160, 255, 0.09)',
  chipText: '#95A8C8',
  chipPressed: 'rgba(120, 160, 255, 0.20)',
  chipSelectedBg: '#77A6FF',
  chipSelectedText: '#FFFFFF',
  outlineButtonBorder: 'rgba(120, 160, 255, 0.20)',
  outlineButtonText: '#AEBFDA',
  outlineButtonPressed: 'rgba(120, 160, 255, 0.10)',
  termBackground: '#0E1421',
  termForeground: '#E4EBF7',
  termMuted: '#6E82A4',
}

export const LightPalette: AppPalette = {
  screenBackground: '#F4F5F8',
  listBackground: '#FFFFFF',
  cardBackground: '#FFFFFF',
  cardBorder: 'rgba(16, 24, 40, 0.07)',
  divider: 'rgba(16, 24, 40, 0.07)',
  dividerStrong: 'rgba(16, 24, 40, 0.12)',
  rowPressed: '#F6F7FA',
  titleText: '#101726',
  rowTitleText: '#111827',
  pathText: '#6B7486',
  metaText: '#6B7486',
  bodyText: '#6B7486',
  accent: '#0B57D0',
  accentContainer: 'rgba(11, 87, 208, 0.09)',
  accentContainerPressed: 'rgba(11, 87, 208, 0.19)',
  onAccent: '#FFFFFF',
  starOn: '#0B57D0',
  starOff: 'rgba(16, 24, 40, 0.24)',
  busyChipBg: 'rgba(18, 165, 148, 0.13)',
  busyChipText: '#0E6E63',
  busyDot: '#12A594',
  idleChipBg: 'rgba(16, 24, 40, 0.05)',
  idleChipText: '#5F6980',
  unknownChipBg: 'rgba(192, 58, 98, 0.13)',
  unknownChipText: '#B23A63',
  unknownDot: '#C03A62',
  statusPillBg: 'rgba(18, 165, 148, 0.13)',
  statusPillText: '#0F766E',
  navBackground: '#F4F5F8',
  navRail: '#0B57D0',
  navActive: '#0B57D0',
  navInactive: '#5F6980',
  consoleBackground: '#EBEDF2',
  keycapBackground: '#FCFCFD',
  keycapBorder: 'rgba(16, 24, 40, 0.10)',
  keycapTopHighlight: 'transparent',
  keycapText: '#2B3446',
  keycapPressed: '#EDEFF4',
  keycapDangerBorder: 'rgba(232, 112, 154, 0.34)',
  keycapDangerTopHighlight: 'transparent',
  keycapDangerText: '#B23A63',
  keycapDangerPressed: '#FCEFF3',
  arrowClusterTrack: 'rgba(16, 24, 40, 0.05)',
  inputBackground: '#FFFFFF',
  inputBorder: 'rgba(16, 24, 40, 0.13)',
  inputText: '#111827',
  inputPlaceholder: '#8A93A5',
  promptGlyph: '#12A594',
  sendEnabledBg: '#0B57D0',
  sendEnabledFg: '#FFFFFF',
  sendDisabledBg: 'rgba(16, 24, 40, 0.07)',
  sendDisabledFg: 'rgba(16, 24, 40, 0.28)',
  scrim: 'rgba(6, 10, 18, 0.52)',
  sheetBackground: '#F7F8FB',
  sheetSurface: '#FFFFFF',
  sheetGrabber: 'rgba(16, 24, 40, 0.20)',
  sheetRowPressed: '#EEF2FA',
  sheetCurrentRowBg: 'rgba(11, 87, 208, 0.04)',
  sheetCurrentRail: '#0B57D0',
  currentBadgeText: '#0B57D0',
  currentBadgeBorder: 'rgba(11, 87, 208, 0.28)',
  segmentedTrack: 'rgba(16, 24, 40, 0.05)',
  segmentedSelectedBg: '#FFFFFF',
  segmentedSelectedText: '#0B57D0',
  segmentedText: '#5E6879',
  chipBg: 'rgba(16, 24, 40, 0.05)',
  chipText: '#5E6879',
  chipPressed: 'rgba(16, 24, 40, 0.12)',
  chipSelectedBg: '#0B57D0',
  chipSelectedText: '#FFFFFF',
  outlineButtonBorder: 'rgba(16, 24, 40, 0.14)',
  outlineButtonText: '#3D4761',
  outlineButtonPressed: '#F1F3F7',
  termBackground: '#F7F8FB',
  termForeground: '#1A2233',
  termMuted: '#6B7486',
}

export const FONT_SIZE_STEPS = [4, 6, 8, 10, 12, 14, 16, 18, 20] as const

export function paletteToCssVars(p: AppPalette): Record<string, string> {
  return {
    '--screen-bg': p.screenBackground,
    '--list-bg': p.listBackground,
    '--card-bg': p.cardBackground,
    '--card-border': p.cardBorder,
    '--divider': p.divider,
    '--divider-strong': p.dividerStrong,
    '--row-pressed': p.rowPressed,
    '--title': p.titleText,
    '--row-title': p.rowTitleText,
    '--path': p.pathText,
    '--meta': p.metaText,
    '--body': p.bodyText,
    '--accent': p.accent,
    '--accent-container': p.accentContainer,
    '--accent-container-pressed': p.accentContainerPressed,
    '--on-accent': p.onAccent,
    '--star-on': p.starOn,
    '--star-off': p.starOff,
    '--busy-chip-bg': p.busyChipBg,
    '--busy-chip-text': p.busyChipText,
    '--busy-dot': p.busyDot,
    '--idle-chip-bg': p.idleChipBg,
    '--idle-chip-text': p.idleChipText,
    '--unknown-chip-bg': p.unknownChipBg,
    '--unknown-chip-text': p.unknownChipText,
    '--unknown-dot': p.unknownDot,
    '--status-pill-bg': p.statusPillBg,
    '--status-pill-text': p.statusPillText,
    '--nav-bg': p.navBackground,
    '--nav-rail': p.navRail,
    '--nav-active': p.navActive,
    '--nav-inactive': p.navInactive,
    '--console-bg': p.consoleBackground,
    '--keycap-bg': p.keycapBackground,
    '--keycap-border': p.keycapBorder,
    '--keycap-hi': p.keycapTopHighlight,
    '--keycap-text': p.keycapText,
    '--keycap-pressed': p.keycapPressed,
    '--keycap-danger-border': p.keycapDangerBorder,
    '--keycap-danger-hi': p.keycapDangerTopHighlight,
    '--keycap-danger-text': p.keycapDangerText,
    '--keycap-danger-pressed': p.keycapDangerPressed,
    '--arrow-track': p.arrowClusterTrack,
    '--input-bg': p.inputBackground,
    '--input-border': p.inputBorder,
    '--input-text': p.inputText,
    '--input-placeholder': p.inputPlaceholder,
    '--prompt': p.promptGlyph,
    '--send-bg': p.sendEnabledBg,
    '--send-fg': p.sendEnabledFg,
    '--send-disabled-bg': p.sendDisabledBg,
    '--send-disabled-fg': p.sendDisabledFg,
    '--scrim': p.scrim,
    '--sheet-bg': p.sheetBackground,
    '--sheet-surface': p.sheetSurface,
    '--sheet-grabber': p.sheetGrabber,
    '--sheet-row-pressed': p.sheetRowPressed,
    '--sheet-current-bg': p.sheetCurrentRowBg,
    '--sheet-current-rail': p.sheetCurrentRail,
    '--current-badge-text': p.currentBadgeText,
    '--current-badge-border': p.currentBadgeBorder,
    '--seg-track': p.segmentedTrack,
    '--seg-selected-bg': p.segmentedSelectedBg,
    '--seg-selected-text': p.segmentedSelectedText,
    '--seg-text': p.segmentedText,
    '--chip-bg': p.chipBg,
    '--chip-text': p.chipText,
    '--chip-pressed': p.chipPressed,
    '--chip-selected-bg': p.chipSelectedBg,
    '--chip-selected-text': p.chipSelectedText,
    '--outline-border': p.outlineButtonBorder,
    '--outline-text': p.outlineButtonText,
    '--outline-pressed': p.outlineButtonPressed,
    '--term-bg': p.termBackground,
    '--term-fg': p.termForeground,
    '--term-muted': p.termMuted,
  }
}
