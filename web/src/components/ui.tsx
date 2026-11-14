import { createContext, useContext, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import styles from './ui.module.css'

type Tone = 'primary' | 'secondary' | 'danger' | 'text'
export function Button({ tone = 'primary', loading, className = '', children, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: Tone, loading?: boolean }) {
  return <button {...props} className={`${styles.button} ${styles[tone]} ${className}`} disabled={loading || props.disabled}>{loading ? '처리 중…' : children}</button>
}
export function PageHeader({ eyebrow, title, description, action }: { eyebrow?: string, title: string, description?: string, action?: ReactNode }) {
  return <header className={styles.pageHeader}><div>{eyebrow && <p className={styles.eyebrow}>{eyebrow}</p>}<h1>{title}</h1>{description && <p>{description}</p>}</div>{action}</header>
}
export function SectionHeader({ title, description, action }: { title: string, description?: string, action?: ReactNode }) {
  return <header className={styles.sectionHeader}><div><h2>{title}</h2>{description && <p>{description}</p>}</div>{action}</header>
}
export function FormField({ label, help, error, children }: { label: string, help?: string, error?: string, children: ReactNode }) {
  return <label className={styles.field}><span>{label}</span>{children}{error ? <small className={styles.error}>{error}</small> : help && <small>{help}</small>}</label>
}
export function StatusBadge({ value }: { value?: string }) {
  const labels: Record<string, string> = { OPEN: '진행 중', UPCOMING: '모집 예정', CLOSED: '마감', UNKNOWN: '상태 미확인', REVIEW_REQUIRED: '확인 필요', READY_TO_PUBLISH: '반영 가능', PDF_REQUIRED: 'PDF 필요', COMPLETED: '반영 완료', DETAIL_AVAILABLE: '상세 확보', PDF_UNSUPPORTED: 'PDF 미지원', FAILED: '분석 실패', DRAFT: '초안', COMMITTED: '반영 완료' }
  return <span className={styles.badge}>{labels[value || ''] || value || '미기재'}</span>
}
export function EmptyState({ children }: { children: ReactNode }) { return <p className={styles.empty}>{children}</p> }
export function ErrorState({ error, retry }: { error: Error, retry?: () => void }) { return <div className={styles.errorState}><p>{error.message}</p>{retry && <Button tone="secondary" onClick={retry}>다시 시도</Button>}</div> }
export function LoadingState() { return <p className={styles.loading}>불러오는 중입니다…</p> }
type Toast = { id: number, message: string, tone: 'success' | 'error' }
const ToastContext = createContext<(message: string, tone?: Toast['tone']) => void>(() => {})
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([])
  const show = (message: string, tone: Toast['tone'] = 'success') => { const id = Date.now(); setItems([{ id, message, tone }]); window.setTimeout(() => setItems([]), 3500) }
  return <ToastContext.Provider value={show}>{children}<div className={styles.toasts} aria-live="polite">{items.map(item => <p key={item.id} className={item.tone === 'error' ? styles.toastError : styles.toast}>{item.message}</p>)}</div></ToastContext.Provider>
}
export const useToast = () => useContext(ToastContext)
