import type { Session } from '../types'
import { OfflineChip, StatusChip } from './StatusChip'
import { StarButton } from './StarButton'

interface Props {
  open: boolean
  workspaceName: string
  sessions: Session[]
  currentSessionId: string
  onDismiss: () => void
  onSelect: (session: Session) => void
  onToggleStar: (id: string) => void
}

export function SessionSwitchSheet({
  open,
  workspaceName,
  sessions,
  currentSessionId,
  onDismiss,
  onSelect,
  onToggleStar,
}: Props) {
  if (!open) return null

  return (
    <>
      <button type="button" className="scrim" aria-label="关闭切换器" onClick={onDismiss} />
      <div className="sheet" role="dialog" aria-label="切换会话">
        <div className="grabber-row">
          <div className="grabber" />
        </div>
        <div className="sheet-head">
          <h2>切换会话</h2>
          <div className="ws-meta">
            {workspaceName} · {sessions.length}
          </div>
        </div>
        <div className="sheet-list">
          {sessions.map((item, i) => {
            const current = item.id === currentSessionId
            return (
              <div
                key={item.id}
                className={`row sheet-row${current ? ' current' : ''}`}
                style={{ animationDelay: `${40 + i * 34}ms` }}
              >
                {current ? <div className="current-rail" /> : null}
                <StarButton starred={item.starred} onToggle={() => onToggleStar(item.id)} />
                <button
                  type="button"
                  className="row-body"
                  onClick={() => onSelect(item)}
                  style={{ background: 'transparent', minHeight: 58, textAlign: 'left' }}
                >
                  <div className="row-title">{item.displayName}</div>
                </button>
                {current ? <span className="badge-now">当前</span> : null}
                {item.isOnline ? <StatusChip status={item.status} /> : <OfflineChip />}
              </div>
            )
          })}
        </div>
      </div>
    </>
  )
}
