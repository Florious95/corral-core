import type { CSSProperties, ReactNode } from 'react'
import { DarkPalette, LightPalette, paletteToCssVars } from '../tokens'
import type { ThemeName } from '../types'

export function PhoneFrame({
  theme,
  children,
}: {
  theme: ThemeName
  children: ReactNode
}) {
  const palette = theme === 'dark' ? DarkPalette : LightPalette
  const vars = paletteToCssVars(palette) as CSSProperties

  return (
    <div className="phone">
      <div className="phone-inner" data-theme={theme} style={vars}>
        <div className="status-bar">
          <span>9:41</span>
          <div className="status-icons" aria-hidden>
            <span>●●●●</span>
            <span>Wi‑Fi</span>
            <span>100%</span>
          </div>
        </div>
        <div className="app">{children}</div>
        <div className="home-bar">
          <span />
        </div>
      </div>
    </div>
  )
}
