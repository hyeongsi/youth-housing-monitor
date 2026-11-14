import { Button } from '../../components/ui'
import type { RecordValue } from '../../api'

type Props = {
  evidence: RecordValue[]
  selected: number | null
  history: RecordValue[]
  diff: RecordValue | null
  onSelectEvidence: (index: number) => void
  onReviewEvidence: (path: string, status: string) => void
  onShowDiff: (revision: RecordValue) => void
  onRestore: (revision: RecordValue) => void
}

export function ReviewSidebar({ evidence, selected, history, diff, onSelectEvidence, onReviewEvidence, onShowDiff, onRestore }: Props) {
  const visibleEvidence = evidence.filter(item => !item.deleted)
  const selectedItem = selected === null ? null : evidence[selected]
  return <aside>
    <h2>원문 근거</h2>
    {visibleEvidence.map((item, index) => <button className="evidenceItem" key={item.id || index} onClick={() => onSelectEvidence(evidence.indexOf(item))}><strong>{item.fieldPath}</strong><br />PDF {item.pageNumber}쪽 · {item.reviewStatus}<br />{item.rawText}</button>)}
    {selectedItem && <div className="evidenceCurrent"><p>{selectedItem.rawText}</p><Button tone="secondary" onClick={() => onReviewEvidence(selectedItem.fieldPath, 'CONFIRMED')}>원문과 같음</Button><Button tone="secondary" onClick={() => onReviewEvidence(selectedItem.fieldPath, 'MANUALLY_REVIEWED')}>직접 수정함</Button></div>}
    <h2>변경 이력</h2>
    {history.map(item => <div className="revision" key={item.id}><p>{item.action} · {new Date(item.createdAt).toLocaleString('ko-KR')}</p><Button tone="text" onClick={() => onShowDiff(item)}>변경 내용 보기</Button><Button tone="text" onClick={() => onRestore(item)}>이 시점으로 복원</Button></div>)}
    {diff && <pre className="diff">{JSON.stringify(diff.changes || diff, null, 2)}</pre>}
  </aside>
}
