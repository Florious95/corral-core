import type { Session, TermLine, Workspace } from './types'

export const WORKSPACES: Workspace[] = [
  {
    id: 'ws-collab',
    name: '多agent协作',
    path: '/Users/alauda/Documents/前沿探索/多agent协作',
  },
  {
    id: 'ws-grok',
    name: 'grok开启team',
    path: '/Users/alauda/Documents/grok开启team',
  },
  {
    id: 'ws-wiki',
    name: '讨论team-agent',
    path: '/Volumes/nvme/Projects/讨论team-agent',
  },
  {
    id: 'ws-corral',
    name: '远程Agent安卓',
    path: '/Volumes/nvme/Projects/远程Agent安卓',
  },
  {
    id: 'ws-explore',
    name: '前沿探索',
    path: '/Users/alauda/Documents/前沿探索',
  },
]

export const INITIAL_SESSIONS: Session[] = [
  {
    id: 's-remote-leader',
    workspaceId: 'ws-corral',
    displayName: '远控 leader',
    path: '/Volumes/nvme/Projects/远程Agent安卓',
    status: 'busy',
    starred: true,
    isOnline: true,
  },
  {
    id: 's-advisor',
    workspaceId: 'ws-corral',
    displayName: 'advisor',
    path: '/Volumes/nvme/Projects/远程Agent安卓',
    status: 'idle',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-ux',
    workspaceId: 'ws-corral',
    displayName: 'ux-v1-layout',
    path: '/Volumes/nvme/Projects/远程Agent安卓',
    status: 'idle',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-claude',
    workspaceId: 'ws-corral',
    displayName: 'claude_code',
    path: '/Volumes/nvme/Projects/远程Agent安卓',
    status: 'busy',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-team-2',
    workspaceId: 'ws-corral',
    displayName: 'team-leader-2',
    path: '/Volumes/nvme/Projects/远程Agent安卓',
    status: 'idle',
    starred: true,
    isOnline: true,
  },
  {
    id: 's-leader',
    workspaceId: 'ws-wiki',
    displayName: 'leader',
    path: '/Volumes/nvme/Projects/讨论team-agent',
    status: 'idle',
    starred: true,
    isOnline: true,
  },
  {
    id: 's-wiki-lead',
    workspaceId: 'ws-wiki',
    displayName: 'wiki-team',
    path: '/Volumes/nvme/Projects/讨论team-agent',
    status: 'busy',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-offline',
    workspaceId: 'ws-wiki',
    displayName: '归档席',
    path: '/Volumes/nvme/Projects/讨论team-agent',
    status: 'unknown',
    starred: true,
    isOnline: false,
  },
  {
    id: 's-collab-1',
    workspaceId: 'ws-collab',
    displayName: '编排主席',
    path: '/Users/alauda/Documents/前沿探索/多agent协作',
    status: 'busy',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-collab-2',
    workspaceId: 'ws-collab',
    displayName: '审查席',
    path: '/Users/alauda/Documents/前沿探索/多agent协作',
    status: 'idle',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-grok-1',
    workspaceId: 'ws-grok',
    displayName: 'grok-luna',
    path: '/Users/alauda/Documents/grok开启team',
    status: 'unknown',
    starred: false,
    isOnline: true,
  },
  {
    id: 's-explore-1',
    workspaceId: 'ws-explore',
    displayName: '探索笔记',
    path: '/Users/alauda/Documents/前沿探索',
    status: 'idle',
    starred: false,
    isOnline: true,
  },
]

let lineSeq = 0
function line(kind: TermLine['kind'], text: string): TermLine {
  lineSeq += 1
  return { id: `ln-${lineSeq}`, kind, text }
}

export function seedTerminal(session: Session): TermLine[] {
  switch (session.id) {
    case 's-claude':
      return [
        line('out', '正在 wiki-team 等待编排信号。'),
        line('out', '工作区：/Volumes/nvme/Projects/讨论team-agent'),
        line('meta', '* Worked for 1m 54s'),
        line('in', '保持在 wiki-team, 需要动无等编排时再临时切'),
        line('busy', session.displayName),
        line('ctx', 'Ctx: 313953 (31%) | Opus 5 (1M context) | 讨…'),
        line('perm', '▸▸ bypass permissions on (shift+tab to cycle)'),
      ]
    case 's-remote-leader':
      return [
        line('out', '远控链路已接通 · 蜂窝 + 广州中转'),
        line('out', '会话打开：秒进、无空白（金标准对照）。'),
        line('meta', '* Worked for 4m 12s'),
        line('in', '继续盯性能门，不要回退 0822 基线'),
        line('busy', session.displayName),
        line('ctx', 'Ctx: 365830 (37%) | Opus 5 (1M context) | 远…'),
        line('perm', '▸▸ bypass permissions on · 1 shell'),
      ]
    case 's-leader':
      return [
        line('out', 'leader 席空闲，等待下一格派单。'),
        line('meta', '* last active 12m ago'),
        line('ctx', 'Ctx: 188420 (19%) | luna | 讨论team-agent'),
      ]
    default:
      return [
        line('out', `已附着 ${session.displayName}`),
        line('out', session.path),
        line('meta', session.status === 'busy' ? '* 正在处理…' : '* 空闲'),
        line('ctx', `Ctx: — | ${session.displayName}`),
      ]
  }
}

export const FAKE_LOGS = [
  '14:02:11 I PairingStore 档案就绪 host=preview.local',
  '14:02:12 I Conn      通道=LAN rtt=4ms',
  '14:02:18 I PerfTrace open.session id=s-claude dt=180ms',
  '14:02:19 W Tsnet     本预览无真实 tailnet（已脱敏）',
  '14:03:01 I Shell     keycap Esc → 模拟注入',
]
