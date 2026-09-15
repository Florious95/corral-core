export type SessionStatus = 'busy' | 'idle' | 'unknown'
export type NavTab = 'favorites' | 'sessions' | 'settings'
export type Appearance = 'dark' | 'light' | 'system'
export type ThemeName = 'dark' | 'light'

export interface Workspace {
  id: string
  name: string
  path: string
}

export interface Session {
  id: string
  workspaceId: string
  displayName: string
  path: string
  status: SessionStatus
  starred: boolean
  isOnline: boolean
}

export type TermLineKind = 'out' | 'in' | 'meta' | 'ctx' | 'perm' | 'busy' | 'key'

export interface TermLine {
  id: string
  kind: TermLineKind
  text: string
}

export type Route =
  | { kind: 'tab'; tab: NavTab }
  | { kind: 'session-list'; workspaceId: string }
  | { kind: 'shell'; workspaceId: string; sessionId: string }
