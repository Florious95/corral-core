import { PathText } from '../components/PathText'
import type { Session, Workspace } from '../types'

export function WorkspaceListScreen({
  workspaces,
  sessions,
  onOpen,
}: {
  workspaces: Workspace[]
  sessions: Session[]
  onOpen: (id: string) => void
}) {
  const total = sessions.length

  return (
    <div className="screen fade-in">
      <header className="screen-header">
        <div>
          <h1>工作区</h1>
          <p className="screen-meta">
            {workspaces.length} WORKSPACES · {total} SESSIONS
          </p>
        </div>
        <span className="lan-pill">LAN</span>
      </header>
      <div className="list-surface">
        {workspaces.map((ws) => {
          const count = sessions.filter((s) => s.workspaceId === ws.id).length
          return (
            <button key={ws.id} type="button" className="row" onClick={() => onOpen(ws.id)}>
              <div className="glyph" aria-hidden>
                ❯
              </div>
              <div className="row-body">
                <div className="row-title">{ws.name}</div>
                <PathText path={ws.path} />
              </div>
              <div className="row-count">
                <strong>{count}</strong>
                <span>会话</span>
              </div>
              <span className="chev" aria-hidden>
                ›
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
