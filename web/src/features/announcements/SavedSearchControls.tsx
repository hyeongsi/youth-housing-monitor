import { useState } from 'react'
import { Button } from '../../components/ui'
import { deleteSearch, saveSearch, savedSearches, type CatalogFilters, type SavedSearch } from './catalogFilters'

type Props = { filters: CatalogFilters; onApply: (filters: CatalogFilters) => void; onMessage: (message: string, tone?: 'success' | 'error') => void }

export function SavedSearchControls({ filters, onApply, onMessage }: Props) {
  const [items, setItems] = useState<SavedSearch[]>(() => savedSearches()); const [selectedId, setSelectedId] = useState(''); const [name, setName] = useState(''); const [editing, setEditing] = useState(false)
  const selected = items.find(item => item.id === selectedId)
  const create = () => {
    const duplicate = items.find(item => item.name === name.trim())
    if (duplicate && !confirm(`‘${duplicate.name}’ 검색 조건을 현재 조건으로 덮어쓸까요?`)) return
    try { const next = saveSearch(name, filters, duplicate?.id); setItems(next); setSelectedId(duplicate?.id || next[0].id); setName(''); setEditing(false); onMessage('검색 조건을 저장했습니다.') } catch (error) { onMessage(error instanceof Error ? error.message : '검색 조건을 저장하지 못했습니다.', 'error') }
  }
  const apply = (id: string) => { const item = items.find(value => value.id === id); setSelectedId(id); if (item) onApply(item.filters) }
  const overwrite = () => { if (!selected) return; if (!confirm(`‘${selected.name}’ 검색 조건을 현재 조건으로 덮어쓸까요?`)) return; setItems(saveSearch(selected.name, filters, selected.id)); onMessage('저장 검색을 현재 조건으로 갱신했습니다.') }
  const remove = () => { if (!selected || !confirm(`‘${selected.name}’ 검색 조건을 삭제할까요?`)) return; setItems(deleteSearch(selected.id)); setSelectedId(''); onMessage('저장 검색을 삭제했습니다.') }
  return <section className="savedSearches" aria-label="저장한 검색 조건"><div><label>저장한 검색<select value={selectedId} onChange={event => apply(event.target.value)}><option value="">선택하세요</option>{items.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><Button tone="secondary" onClick={() => setEditing(value => !value)}>현재 조건 저장</Button>{selected && <><Button tone="text" onClick={overwrite}>덮어쓰기</Button><Button tone="text" onClick={remove}>삭제</Button></>}</div>{editing && <div className="savedSearchEditor"><input autoFocus maxLength={30} value={name} onChange={event => setName(event.target.value)} placeholder="예: 서울 청년 · 보증금 1억 이하" onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); create() } }}/><Button onClick={create}>저장</Button><Button tone="text" onClick={() => { setName(''); setEditing(false) }}>취소</Button></div>}</section>
}
