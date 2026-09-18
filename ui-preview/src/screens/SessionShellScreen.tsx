import { useEffect, useRef } from 'react'
import type { Session, TermLine } from '../types'

const KEYS = [
  { id: 'Esc', label: 'Esc', danger: false, kind: 'text' as const },
  { id: 'Tab', label: 'Tab', danger: false, kind: 'text' as const },
  { id: 'Up', label: '↑', danger: false, kind: 'arrow' as const },
  { id: 'Down', label: '↓', danger: false, kind: 'arrow' as const },
  { id: 'Left', label: '←', danger: false, kind: 'arrow' as const },
  { id: 'Right', label: '→', danger: false, kind: 'arrow' as const },
  { id: 'Ctrl-C', label: 'Ctrl-C', danger: true, kind: 'danger' as const },
]

function TermLines({ lines, fontSize }: { lines: TermLine[]; fontSize: number }) {
  const end = useRef<HTMLDivElement>(null)
  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [lines.length])

  return (
    <div className="term-card" style={{ fontSize }}>
      {lines.map((ln) =>
        ln.kind === 'busy' ? (
          <div key={ln.id} className="busy-float">
            {ln.text}
          </div>
        ) : (
          <div key={ln.id} className={`term-line ${ln.kind}`}>
            {ln.kind === 'in' ? <span className="term-prompt">› </span> : null}
            {ln.text}
          </div>
        ),
      )}
      <div ref={end} />
    </div>
  )
}

export function SessionShellScreen({
  session,
  lines,
  draft,
  fontSize,
  onDraft,
  onSend,
  onBack,
  onOpenSwitcher,
  onKey,
  onAttach,
}: {
  session: Session
  lines: TermLine[]
  draft: string
  fontSize: number
  onDraft: (v: string) => void
  onSend: () => void
  onBack: () => void
  onOpenSwitcher: () => void
  onKey: (label: string) => void
  onAttach: () => void
}) {
  return (
    <div className="screen push-in">
      <div className="topbar">
        <button type="button" className="icon-btn" onClick={onBack} aria-label="返回">
          <svg width="18" height="18" viewBox="0 0 22 22" aria-hidden>
            <path
              d="M14.1 4.8L7.5 11l6.6 6.2"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
        <div className="shell-title">
          <i className={`lamp ${session.status}`} />
          <div className="shell-name">{session.displayName}</div>
        </div>
        <span className="lan-pill">LAN</span>
        <button type="button" className="tonal-btn" onClick={onOpenSwitcher}>
          查看
        </button>
      </div>
      <TermLines lines={lines} fontSize={fontSize} />
      <div className="console">
        <div className="keys">
          <div className="key-group">
            {KEYS.filter((k) => k.kind === 'text').map((k) => (
              <button key={k.id} type="button" className="keycap" onClick={() => onKey(k.label)}>
                {k.label}
              </button>
            ))}
          </div>
          <div className="arrows">
            {KEYS.filter((k) => k.kind === 'arrow').map((k) => (
              <button key={k.id} type="button" className="arrow-key" onClick={() => onKey(k.label)}>
                {k.label}
              </button>
            ))}
          </div>
          <button type="button" className="keycap danger" onClick={() => onKey('Ctrl-C')}>
            Ctrl-C
          </button>
        </div>
        <div className="composer">
          <button type="button" className="plus" onClick={onAttach} aria-label="附加">
            +
          </button>
          <form
            className="draft"
            onSubmit={(e) => {
              e.preventDefault()
              onSend()
            }}
          >
            <span className="prompt-glyph" aria-hidden>
              ›
            </span>
            <input
              value={draft}
              onChange={(e) => onDraft(e.target.value)}
              placeholder="输入指令…"
              aria-label="输入指令"
            />
          </form>
          <button type="button" className="send" disabled={!draft.trim()} onClick={onSend} aria-label="发送">
            ↑
          </button>
        </div>
      </div>
    </div>
  )
}
