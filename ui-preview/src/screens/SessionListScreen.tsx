import { PathText } from '../components/PathText'
import { StatusChip } from '../components/StatusChip'
import { StarButton } from '../components/StarButton'
import type { Session, Workspace } from '../types'

export function SessionListScreen({
  workspace,
  sessions,
  onBack,
  onOpen,
  onToggleStar,
}: {
  workspace: Workspace
  sessions: Session[]
  onBack: () => void
  onOpen: (s: Session) => void
  onToggleStar: (id: string) => void
}) {
  return (
    <div className="screen push-in">
      <div className="topbar">
        <button type="button" className="back-affordance" onClick={onBack}>
          ‹ 工作区
        </button>
        <div style={{ flex: 1 }} />
        <span className="lan-pill">LAN</span>
      </div>
      <div className="subhead">
        <h2>{workspace.name}</h2>
        <PathText path={workspace.path} />
      </div>
      <div className="list-surface">
        {sessions.map((item) => (
          <div key={item.id} className="row" style={{ minHeight: 60 }}>
            <StarButton starred={item.starred} onToggle={() => onToggleStar(item.id)} />
            <button
              type="button"
              className="row-body"
              onClick={() => onOpen(item)}
              style={{ background: 'transparent', minHeight: 60, textAlign: 'left' }}
            >
              <div className="row-title">{item.displayName}</div>
            </button>
            <StatusChip status={item.status} />
          </div>
        ))}
      </div>
    </div>
  )
}
