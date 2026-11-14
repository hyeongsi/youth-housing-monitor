import { Button, FormActions, FormField, FormGrid } from '../../components/ui'
import { formattedDigits } from '../../api'
import type { CatalogFilters } from './catalogFilters'

type RegionOption = { sido?: string; sigungu?: string; dong?: string }
type Props = { draft: CatalogFilters; sources: string[]; housingTypes: string[]; regionHierarchy: RegionOption[]; hasInterestCriteria: boolean; onChange: (key: string, value: string) => void; onApply: () => void; onReset: () => void }
export function AnnouncementFilterForm({ draft, sources, housingTypes, regionHierarchy, hasInterestCriteria, onChange, onApply, onReset }: Props) {
  const sidos = [...new Set(regionHierarchy.map(value => value.sido).filter(Boolean))] as string[]
  const sigungus = [...new Set(regionHierarchy.filter(value => !draft.sido || value.sido === draft.sido).map(value => value.sigungu).filter(Boolean))] as string[]
  const dongs = [...new Set(regionHierarchy.filter(value => (!draft.sido || value.sido === draft.sido) && (!draft.sigungu || value.sigungu === draft.sigungu)).map(value => value.dong).filter(Boolean))] as string[]
  const areaRangeInvalid = Number(draft.areaMin) > Number(draft.areaMax) && Boolean(draft.areaMax)
  const checkbox = (key: string, label: string) => <label className="filterToggle"><input type="checkbox" checked={draft[key] === 'true'} onChange={event => onChange(key, String(event.target.checked))} /><span>{label}</span></label>
  const wonInput = (key: string, placeholder: string) => <div className="unitInput"><input inputMode="numeric" value={formattedDigits(draft[key] || '')} onChange={event => onChange(key, event.target.value.replace(/\D/g, ''))} placeholder={placeholder}/><span>원</span></div>
  const areaInput = (key: string, placeholder: string) => <div className="unitInput"><input inputMode="decimal" value={draft[key] || ''} onChange={event => onChange(key, event.target.value.replace(/[^\d.]/g, '').replace(/(\..*)\./g, '$1'))} placeholder={placeholder}/><span>㎡</span></div>
  return <section className="panel filters">
    <FormGrid>
      <FormField label="통합 검색"><input value={draft.query || ''} onChange={event => onChange('query', event.target.value)} placeholder="예: 청년안심 관악" /></FormField>
      <FormField label="시·도"><select value={draft.sido || ''} onChange={event => onChange('sido', event.target.value)}><option value="">전체 시·도</option>{sidos.map(value => <option key={value} value={value}>{value}</option>)}</select></FormField>
      <FormField label="시·군·구"><select value={draft.sigungu || ''} onChange={event => onChange('sigungu', event.target.value)} disabled={!draft.sido && !sigungus.length}><option value="">전체 시·군·구</option>{sigungus.map(value => <option key={value} value={value}>{value}</option>)}</select></FormField>
      <FormField label="읍·면·동"><select value={draft.dong || ''} onChange={event => onChange('dong', event.target.value)} disabled={!draft.sigungu && !dongs.length}><option value="">전체 읍·면·동</option>{dongs.map(value => <option key={value} value={value}>{value}</option>)}</select></FormField>
      <FormField label="출처"><select value={draft.source || ''} onChange={event => onChange('source', event.target.value)}><option value="">전체 출처</option>{sources.map(value => <option key={value} value={value}>{value}</option>)}</select></FormField>
      <FormField label="주택 유형"><select value={draft.housingType || ''} onChange={event => onChange('housingType', event.target.value)}><option value="">전체 유형</option>{housingTypes.map(value => <option key={value} value={value}>{value}</option>)}</select></FormField>
      <FormField label="모집 상태"><select value={draft.status || ''} onChange={event => onChange('status', event.target.value)}><option value="">전체 상태</option><option value="OPEN">진행 중</option><option value="UPCOMING">모집 예정</option><option value="CLOSED">마감</option><option value="UNKNOWN">상태 미확인</option></select></FormField>
      <FormField label="보증금 상한">{wonInput('depositMaxWon', '예: 100,000,000')}</FormField>
      <FormField label="월세 상한">{wonInput('rentMaxWon', '예: 500,000')}</FormField>
      <FormField label="전용면적 최소">{areaInput('areaMin', '예: 39.6')}</FormField>
      <FormField label="전용면적 최대" error={areaRangeInvalid ? '최소 면적보다 크게 입력하세요.' : undefined}>{areaInput('areaMax', '예: 59.9')}</FormField>
      <FormField label="정렬"><select value={draft.savedOnly === 'true' ? 'SAVED' : draft.sort || 'LATEST'} disabled={draft.savedOnly === 'true'} onChange={event => onChange('sort', event.target.value)}>{draft.savedOnly === 'true' ? <option value="SAVED">저장 순서</option> : <><option value="LATEST">최신순</option><option value="DEADLINE">마감 임박순</option></>}</select></FormField>
    </FormGrid>
    <fieldset className="filterOptionGroup"><legend>추가 조건</legend><div className="filterToggles">{checkbox('includeClosed', '마감 공고 포함')}{checkbox('includeUnknown', '정보 미확인 공고 포함')}{checkbox('deadlineSoonOnly', '마감 임박 공고만')}{checkbox('interestOnly', '내 관심 조건에 맞는 공고만')}{checkbox('savedOnly', '관심 저장만')}{draft.savedOnly === 'true' && checkbox('reminderDueOnly', '확인 시점 지난 공고만')}{checkbox('supplementalOnly', '추가모집만 보기')}</div></fieldset>
    {draft.interestOnly === 'true' && !hasInterestCriteria && <p className="hint" role="status">관심 조건을 먼저 저장하면 맞춤 공고를 찾을 수 있습니다.</p>}
    <FormActions><Button tone="secondary" type="button" onClick={onReset}>초기화</Button><Button type="button" onClick={onApply} disabled={areaRangeInvalid}>검색</Button></FormActions>
  </section>
}
