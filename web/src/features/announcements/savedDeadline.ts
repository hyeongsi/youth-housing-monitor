const DAY = 24 * 60 * 60 * 1000

export function savedDeadlineLabel(value: unknown, now = Date.now()) {
  if (typeof value !== 'string' || !value.trim()) return undefined
  const end = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T23:59:59`) : new Date(value)
  if (Number.isNaN(end.getTime())) return undefined
  const remaining = Math.ceil((end.getTime() - now) / DAY)
  if (remaining < 0 || remaining > 3) return undefined
  return remaining === 0 ? '오늘 마감' : `마감 D-${remaining}`
}
