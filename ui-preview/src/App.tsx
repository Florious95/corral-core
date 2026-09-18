import { BottomNav } from './components/BottomNav'
import { PhoneFrame } from './components/PhoneFrame'
import { SessionSwitchSheet } from './components/SessionSwitchSheet'
import { FAKE_LOGS, WORKSPACES } from './mockData'
import { FavoritesScreen } from './screens/FavoritesScreen'
import { SessionListScreen } from './screens/SessionListScreen'
import { SessionShellScreen } from './screens/SessionShellScreen'
import { SettingsScreen } from './screens/SettingsScreen'
import { WorkspaceListScreen } from './screens/WorkspaceListScreen'
import { PreviewProvider, usePreview } from './store'
import type { NavTab } from './types'

function navFromRoute(route: ReturnType<typeof usePreview>['route']): NavTab {
  if (route.kind === 'tab') return route.tab
  return 'sessions'
}

function AppShell() {
  const s = usePreview()
  const tab = navFromRoute(s.route)
  const favorites = s.sessions.filter((x) => x.starred)
  const workspaceId = s.route.kind === 'tab' ? undefined : s.route.workspaceId
  const workspace = workspaceId ? WORKSPACES.find((w) => w.id === workspaceId) : undefined
  const workspaceSessions = workspace
    ? s.sessions.filter((x) => x.workspaceId === workspace.id)
    : []
  const sessionId = s.route.kind === 'shell' ? s.route.sessionId : undefined
  const currentSession = sessionId
    ? s.sessions.find((x) => x.id === sessionId)
    : undefined

  const showNav = s.route.kind !== 'shell'

  return (
    <div className="stage">
      <div className="stage-copy">
        <h1>AgentMirror 交互预览</h1>
        <p>
          点底部「收藏 / 会话 / 设置」，从工作区进入会话页，再点「查看」切换。默认深色「终端深夜」，设置里可切浅色。无后端。
        </p>
      </div>
      <PhoneFrame theme={s.theme}>
        {s.route.kind === 'tab' && s.route.tab === 'favorites' ? (
          <FavoritesScreen
            favorites={favorites}
            onOpen={s.openSession}
            onToggleStar={s.toggleStar}
          />
        ) : null}
        {s.route.kind === 'tab' && s.route.tab === 'sessions' ? (
          <WorkspaceListScreen
            workspaces={WORKSPACES}
            sessions={s.sessions}
            onOpen={s.openWorkspace}
          />
        ) : null}
        {s.route.kind === 'tab' && s.route.tab === 'settings' ? (
          <SettingsScreen
            appearance={s.appearance}
            fontSize={s.fontSize}
            paired={s.paired}
            onAppearance={s.setAppearance}
            onFontSize={s.setFontSize}
            onRepair={s.repair}
            onExport={s.exportLogs}
            onViewLogs={s.viewLogs}
          />
        ) : null}
        {s.route.kind === 'session-list' && workspace ? (
          <SessionListScreen
            workspace={workspace}
            sessions={workspaceSessions}
            onBack={s.goBack}
            onOpen={s.openSession}
            onToggleStar={s.toggleStar}
          />
        ) : null}
        {s.route.kind === 'shell' && currentSession ? (
          <SessionShellScreen
            session={currentSession}
            lines={s.terminals[currentSession.id] ?? []}
            draft={s.draft}
            fontSize={s.fontSize}
            onDraft={s.setDraft}
            onSend={s.sendDraft}
            onBack={s.goBack}
            onOpenSwitcher={s.openSwitcher}
            onKey={s.pressKey}
            onAttach={s.attach}
          />
        ) : null}

        {showNav ? <BottomNav selected={tab} onSelect={s.setTab} /> : null}

        {currentSession && workspace ? (
          <SessionSwitchSheet
            open={s.switcherOpen}
            workspaceName={workspace.name}
            sessions={workspaceSessions}
            currentSessionId={currentSession.id}
            onDismiss={s.closeSwitcher}
            onSelect={s.switchSession}
            onToggleStar={s.toggleStar}
          />
        ) : null}

        {s.logsOpen ? (
          <>
            <button type="button" className="scrim" aria-label="关闭日志" onClick={s.closeLogs} />
            <div className="sheet" role="dialog" aria-label="诊断日志">
              <div className="grabber-row">
                <div className="grabber" />
              </div>
              <div className="sheet-head">
                <h2>诊断日志</h2>
              </div>
              <div className="log-body">
                {FAKE_LOGS.map((line) => (
                  <div key={line}>{line}</div>
                ))}
              </div>
            </div>
          </>
        ) : null}

        {s.toast ? <div className="toast">{s.toast}</div> : null}
      </PhoneFrame>
    </div>
  )
}

export default function App() {
  return (
    <PreviewProvider>
      <AppShell />
    </PreviewProvider>
  )
}
