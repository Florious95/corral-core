import { OfflineChip, StatusChip } from '../components/StatusChip'
import { PathText } from '../components/PathText'
import { StarButton } from '../components/StarButton'
import type { Session } from '../types'

export function FavoritesScreen({
  favorites,
  onOpen,
  onToggleStar,
}: {
  favorites: Session[]
  onOpen: (s: Session) => void
  onToggleStar: (id: string) => void
}) {
  const active = favorites.filter((s) => s.status === 'busy' && s.isOnline).length

  return (
    <div className="screen fade-in">
      <header className="screen-header">
        <div>
          <h1>收藏</h1>
          <p className="screen-meta">
            {favorites.length} SESSIONS · {active} ACTIVE
          </p>
        </div>
        <span className="lan-pill">LAN</span>
      </header>
      <div className="list-surface">
        {favorites.length === 0 ? (
          <p className="card-body" style={{ padding: 20 }}>
            还没有收藏。在会话列表点亮星标即可出现在这里。
          </p>
        ) : (
          favorites.map((item) => (
            <div key={item.id} className={`row${item.isOnline ? '' : ' offline'}`}>
              <StarButton starred={item.starred} onToggle={() => onToggleStar(item.id)} />
              <button
                type="button"
                className="row-body"
                onClick={() => onOpen(item)}
                style={{ background: 'transparent', minHeight: 66, textAlign: 'left' }}
              >
                <div className="row-title">{item.displayName}</div>
                <PathText path={item.path} />
              </button>
              {item.isOnline ? <StatusChip status={item.status} /> : <OfflineChip />}
            </div>
          ))
        )}
      </div>
    </div>
  )
}
