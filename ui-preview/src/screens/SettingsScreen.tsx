import { FONT_SIZE_STEPS } from '../tokens'
import type { Appearance } from '../types'

const APPEARANCE: { id: Appearance; label: string }[] = [
  { id: 'light', label: '浅色' },
  { id: 'dark', label: '深色' },
  { id: 'system', label: '跟随系统' },
]

const APPEARANCE_LABEL: Record<Appearance, string> = {
  light: '浅色',
  dark: '深色',
  system: '跟随系统',
}

export function SettingsScreen({
  appearance,
  fontSize,
  paired,
  onAppearance,
  onFontSize,
  onRepair,
  onExport,
  onViewLogs,
}: {
  appearance: Appearance
  fontSize: number
  paired: boolean
  onAppearance: (a: Appearance) => void
  onFontSize: (n: number) => void
  onRepair: () => void
  onExport: () => void
  onViewLogs: () => void
}) {
  return (
    <div className="screen fade-in">
      <header className="screen-header">
        <h1>设置</h1>
      </header>
      <div className="settings">
        <section className="card">
          <div className="card-head">
            <h3>主机配对</h3>
            {paired ? <span className="chip busy">PAIRED</span> : null}
          </div>
          <p className="card-body">当前只保留一个主机档案。重新配对成功后会覆盖现有档案。</p>
          <button type="button" className="card-action" onClick={onRepair}>
            重新配对
          </button>
        </section>

        <section className="card">
          <div className="card-head">
            <h3>字体大小</h3>
            <span className="screen-meta" style={{ margin: 0, color: 'var(--accent)' }}>
              {fontSize} pt
            </span>
          </div>
          <p className="card-body">
            取代原捏合缩放：终端字号在此设置，进入会话前已确定，会话中不再变化。
          </p>
          <div className="font-row">
            {FONT_SIZE_STEPS.map((size) => (
              <button
                key={size}
                type="button"
                className={`font-chip${size === fontSize ? ' on' : ''}`}
                onClick={() => onFontSize(size)}
              >
                {size}
              </button>
            ))}
          </div>
          <div className="preview-line" style={{ fontSize }}>
            <span className="prompt">❯</span>
            <span>claim-leader --team wiki-team</span>
          </div>
        </section>

        <section className="card">
          <h3>诊断日志</h3>
          <p className="card-body">
            一键导出诊断日志，帮助我们定位问题。日志会自动脱敏（配对 token、密钥等不会包含）。
          </p>
          <div className="btn-row">
            <button type="button" className="card-action" onClick={onExport}>
              导出
            </button>
            <button type="button" className="card-outline" onClick={onViewLogs}>
              查看
            </button>
          </div>
        </section>

        <section className="card">
          <div className="card-head">
            <h3>外观</h3>
            <span className="screen-meta" style={{ margin: 0, color: 'var(--accent)' }}>
              {APPEARANCE_LABEL[appearance]}
            </span>
          </div>
          <p className="card-body">列表、设置和外壳走这里。默认人格是「终端深夜」深色。</p>
          <div className="segmented">
            {APPEARANCE.map((opt) => (
              <button
                key={opt.id}
                type="button"
                className={appearance === opt.id ? 'on' : ''}
                onClick={() => onAppearance(opt.id)}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </section>

        <p className="footnote">preview · ui-preview / AgentMirror · 无后端</p>
      </div>
    </div>
  )
}
