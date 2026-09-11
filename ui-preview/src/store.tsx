import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { INITIAL_SESSIONS, seedTerminal } from './mockData'
import type { Appearance, NavTab, Route, Session, TermLine, ThemeName } from './types'

interface PreviewStore {
  appearance: Appearance
  theme: ThemeName
  fontSize: number
  paired: boolean
  route: Route
  sessions: Session[]
  terminals: Record<string, TermLine[]>
  draft: string
  switcherOpen: boolean
  logsOpen: boolean
  toast: string | null
  setAppearance: (a: Appearance) => void
  setFontSize: (n: number) => void
  setTab: (tab: NavTab) => void
  openWorkspace: (workspaceId: string) => void
  openSession: (session: Session) => void
  goBack: () => void
  toggleStar: (id: string) => void
  setDraft: (v: string) => void
  sendDraft: () => void
  pressKey: (label: string) => void
  openSwitcher: () => void
  closeSwitcher: () => void
  switchSession: (session: Session) => void
  repair: () => void
  exportLogs: () => void
  viewLogs: () => void
  closeLogs: () => void
  attach: () => void
  notify: (msg: string) => void
}

const Ctx = createContext<PreviewStore | null>(null)

function systemTheme(): ThemeName {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function resolveTheme(appearance: Appearance): ThemeName {
  if (appearance === 'system') return systemTheme()
  return appearance
}

export function PreviewProvider({ children }: { children: ReactNode }) {
  const [appearance, setAppearanceState] = useState<Appearance>('dark')
  const [theme, setTheme] = useState<ThemeName>('dark')
  const [fontSize, setFontSize] = useState(14)
  const [paired, setPaired] = useState(true)
  const [route, setRoute] = useState<Route>({ kind: 'tab', tab: 'favorites' })
  const [sessions, setSessions] = useState<Session[]>(INITIAL_SESSIONS)
  const [terminals, setTerminals] = useState<Record<string, TermLine[]>>(() => {
    const init: Record<string, TermLine[]> = {}
    for (const s of INITIAL_SESSIONS) init[s.id] = seedTerminal(s)
    return init
  })
  const [draft, setDraft] = useState('')
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const [logsOpen, setLogsOpen] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const [shellReturn, setShellReturn] = useState<Route>({ kind: 'tab', tab: 'favorites' })

  useEffect(() => {
    const apply = () => setTheme(resolveTheme(appearance))
    apply()
    if (appearance !== 'system') return
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    mq.addEventListener('change', apply)
    return () => mq.removeEventListener('change', apply)
  }, [appearance])

  const notify = useCallback((msg: string) => {
    setToast(msg)
    window.setTimeout(() => setToast((cur) => (cur === msg ? null : cur)), 2200)
  }, [])

  const appendLine = useCallback((sessionId: string, kind: TermLine['kind'], text: string) => {
    setTerminals((prev) => ({
      ...prev,
      [sessionId]: [
        ...(prev[sessionId] ?? []),
        { id: `ln-${sessionId}-${Date.now()}-${Math.random().toString(16).slice(2)}`, kind, text },
      ],
    }))
  }, [])

  const currentSessionId = route.kind === 'shell' ? route.sessionId : null

  const value = useMemo<PreviewStore>(
    () => ({
      appearance,
      theme,
      fontSize,
      paired,
      route,
      sessions,
      terminals,
      draft,
      switcherOpen,
      logsOpen,
      toast,
      setAppearance: setAppearanceState,
      setFontSize,
      setTab: (tab) => {
        setSwitcherOpen(false)
        setLogsOpen(false)
        setRoute({ kind: 'tab', tab })
      },
      openWorkspace: (workspaceId) => setRoute({ kind: 'session-list', workspaceId }),
      openSession: (session) => {
        if (!session.isOnline) {
          notify('该会话不在线，无法打开')
          return
        }
        if (route.kind === 'tab' && route.tab === 'favorites') {
          setShellReturn({ kind: 'tab', tab: 'favorites' })
        } else if (route.kind === 'session-list') {
          setShellReturn({ kind: 'session-list', workspaceId: route.workspaceId })
        } else {
          setShellReturn({ kind: 'session-list', workspaceId: session.workspaceId })
        }
        setDraft('')
        setRoute({ kind: 'shell', workspaceId: session.workspaceId, sessionId: session.id })
      },
      goBack: () => {
        setSwitcherOpen(false)
        if (route.kind === 'shell') {
          setRoute(shellReturn)
          return
        }
        if (route.kind === 'session-list') {
          setRoute({ kind: 'tab', tab: 'sessions' })
        }
      },
      toggleStar: (id) => {
        setSessions((prev) =>
          prev.map((s) => (s.id === id ? { ...s, starred: !s.starred } : s)),
        )
      },
      setDraft,
      sendDraft: () => {
        const text = draft.trim()
        if (!text || !currentSessionId) return
        appendLine(currentSessionId, 'in', text)
        appendLine(currentSessionId, 'out', `[模拟回显] 指令已入队 · ${text}`)
        setDraft('')
      },
      pressKey: (label) => {
        if (!currentSessionId) return
        appendLine(currentSessionId, 'key', `[${label}]`)
      },
      openSwitcher: () => setSwitcherOpen(true),
      closeSwitcher: () => setSwitcherOpen(false),
      switchSession: (session) => {
        if (!session.isOnline) {
          notify('该会话不在线，无法切换')
          return
        }
        setDraft('')
        setSwitcherOpen(false)
        setRoute({ kind: 'shell', workspaceId: session.workspaceId, sessionId: session.id })
      },
      repair: () => {
        setPaired(true)
        notify('已模拟重新配对（预览无真实主机）')
      },
      exportLogs: () => notify('已生成脱敏诊断日志（预览）'),
      viewLogs: () => setLogsOpen(true),
      closeLogs: () => setLogsOpen(false),
      attach: () => notify('附件（预览无实际文件）'),
      notify,
    }),
    [
      appearance,
      theme,
      fontSize,
      paired,
      route,
      sessions,
      terminals,
      draft,
      switcherOpen,
      logsOpen,
      toast,
      shellReturn,
      currentSessionId,
      appendLine,
      notify,
    ],
  )

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function usePreview(): PreviewStore {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('usePreview must be used within PreviewProvider')
  return ctx
}
