import { createContext, useContext, type ReactNode } from 'react'
import { Button, FormField } from '../../components/ui'
import type { RecordValue } from '../../api'

type ContextValue = { get: (path: string) => unknown; update: (path: string, value: unknown) => void; related: (path: string) => RecordValue[]; evidence: RecordValue[]; select: (index: number) => void; review: (path: string, status: string) => void }
const ReviewFieldContext = createContext<ContextValue | null>(null)

export function ReviewFieldProvider({ value, children }: { value: ContextValue; children: ReactNode }) { return <ReviewFieldContext.Provider value={value}>{children}</ReviewFieldContext.Provider> }

export function ReviewField({ label, path, type = 'text' }: { label: string; path: string; type?: string }) {
  const context = useContext(ReviewFieldContext)
  if (!context) throw new Error('ReviewField는 ReviewFieldProvider 안에서 사용해야 합니다.')
  const matches = context.related(path)
  const value = context.get(path)
  return <FormField label={label}><input type={type} checked={type === 'checkbox' ? Boolean(value) : undefined} value={type === 'checkbox' ? undefined : String(value ?? '')} onChange={event => context.update(path, type === 'checkbox' ? event.target.checked : type === 'number' ? (event.target.value === '' ? null : Number(event.target.value)) : event.target.value)} /><span className="fieldEvidence">{matches[0] && <Button type="button" tone="text" onClick={() => context.select(context.evidence.indexOf(matches[0]))}>근거 {matches[0].pageNumber}쪽</Button>}<Button type="button" tone="text" onClick={() => context.review(path, matches.length ? 'CONFIRMED' : 'MANUALLY_REVIEWED')}>{matches.length ? '원문과 같음' : '직접 수정함'}</Button></span></FormField>
}
