interface Props {
  starred: boolean
  onToggle: () => void
  label?: string
}

export function StarButton({ starred, onToggle, label }: Props) {
  return (
    <button
      type="button"
      className={`star${starred ? ' on' : ''}`}
      onClick={(e) => {
        e.stopPropagation()
        onToggle()
      }}
      aria-label={label ?? (starred ? '取消收藏' : '加入收藏')}
      aria-pressed={starred}
    >
      {starred ? '★' : '☆'}
    </button>
  )
}
