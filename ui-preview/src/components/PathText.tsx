import { truncatePath } from '../pathText'

export function PathText({ path }: { path: string }) {
  return (
    <div className="path" title={path}>
      {truncatePath(path)}
    </div>
  )
}
