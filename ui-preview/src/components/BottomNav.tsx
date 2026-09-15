import type { NavTab } from '../types'

const TABS: { id: NavTab; glyph: string; label: string }[] = [
  { id: 'favorites', glyph: '★', label: '收藏' },
  { id: 'sessions', glyph: '☰', label: '会话' },
  { id: 'settings', glyph: '⚙', label: '设置' },
]

export function BottomNav({
  selected,
  onSelect,
}: {
  selected: NavTab
  onSelect: (tab: NavTab) => void
}) {
  const index = TABS.findIndex((t) => t.id === selected)
  const cell = 100 / TABS.length
  const offset = `calc(${index * cell}% + ${cell / 2}% - 22px)`

  return (
    <nav className="bottom-nav" aria-label="主导航">
      {TABS.map((tab) => (
        <button
          key={tab.id}
          type="button"
          className={`nav-cell${selected === tab.id ? ' active' : ''}`}
          data-tab={tab.id}
          onClick={() => onSelect(tab.id)}
          aria-current={selected === tab.id ? 'page' : undefined}
        >
          <span className="nav-glyph" aria-hidden>
            {tab.glyph}
          </span>
          <span className="nav-label">{tab.label}</span>
        </button>
      ))}
      <div className="nav-rail" style={{ left: offset }} />
    </nav>
  )
}
