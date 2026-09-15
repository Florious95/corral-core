/**
 * 路径可读截断：保留根段 + 末段，比行尾省略或随机中段切断更好认。
 * 例：/Volumes/nvme/Projects/远程Agent安卓 → /Volumes/nvme/…/远程Agent安卓
 */
export function truncatePath(path: string, max = 30): string {
  if (path.length <= max) return path
  const abs = path.startsWith('/')
  const segs = path.split('/').filter(Boolean)
  if (segs.length === 0) return path
  if (segs.length === 1) {
    const name = segs[0]
    const keep = Math.max(6, max - 2)
    return (abs ? '/' : '') + name.slice(0, 4) + '…' + name.slice(-(keep - 5))
  }

  const last = segs[segs.length - 1]
  const root = (abs ? '/' : '') + segs[0]
  const mid = segs.length > 2 ? segs[1] : ''

  const withMid = mid ? `${root}/${mid}/…/${last}` : `${root}/…/${last}`
  if (withMid.length <= max) return withMid

  const compact = `${root}/…/${last}`
  if (compact.length <= max) return compact

  const budget = Math.max(8, max - root.length - 4)
  if (last.length <= budget) return `${root}/…/${last}`
  const head = Math.max(2, Math.ceil(budget * 0.4))
  const tail = Math.max(4, budget - head - 1)
  return `${root}/…/${last.slice(0, head)}…${last.slice(-tail)}`
}
