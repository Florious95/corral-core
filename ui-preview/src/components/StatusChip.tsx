import type { SessionStatus } from '../types'

const LABELS: Record<SessionStatus, string> = {
  busy: '进行中',
  idle: '空闲',
  unknown: '未知',
}

export function StatusChip({ status }: { status: SessionStatus }) {
  return (
    <span className={`chip ${status}`}>
      <i className="dot" />
      {LABELS[status]}
    </span>
  )
}

export function OfflineChip() {
  return <span className="chip offline">不在线</span>
}
