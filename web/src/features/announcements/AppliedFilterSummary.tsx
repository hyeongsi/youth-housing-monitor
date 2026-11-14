import type { CatalogFilters } from './catalogFilters'

type Props = { filters: CatalogFilters; onRemove: (key: string) => void; onReset: () => void }

const statusLabels: Record<string, string> = { OPEN: '진행 중', UPCOMING: '모집 예정', CLOSED: '마감', UNKNOWN: '상태 미확인' }
const currency = (value: string) => `${Number(value).toLocaleString('ko-KR')}원 이하`

export function AppliedFilterSummary({ filters, onRemove, onReset }: Props) {
  const region = filters.dong || filters.sigungu || filters.sido || filters.region
  const chips = [
    region && { key: 'region', label: `지역: ${region}` },
    filters.query && { key: 'query', label: `검색어: ${filters.query}` },
    filters.source && { key: 'source', label: `출처: ${filters.source}` },
    filters.housingType && { key: 'housingType', label: `주택 유형: ${filters.housingType}` },
    filters.status && { key: 'status', label: `모집 상태: ${statusLabels[filters.status] || filters.status}` },
    filters.depositMaxWon && { key: 'depositMaxWon', label: `보증금: ${currency(filters.depositMaxWon)}` },
    filters.rentMaxWon && { key: 'rentMaxWon', label: `월세: ${currency(filters.rentMaxWon)}` },
    (filters.areaMin || filters.areaMax) && { key: 'area', label: `전용면적: ${filters.areaMin || '0'}~${filters.areaMax || '제한 없음'}㎡` },
    filters.includeClosed === 'true' && { key: 'includeClosed', label: '마감 공고 포함' },
    filters.includeUnknown === 'false' && { key: 'includeUnknown', label: '정보 확인 공고만' },
    filters.deadlineSoonOnly === 'true' && { key: 'deadlineSoonOnly', label: '마감 임박 공고만' },
    filters.interestOnly === 'true' && { key: 'interestOnly', label: '내 관심 조건 일치' },
    filters.savedOnly === 'true' && { key: 'savedOnly', label: '관심 저장만' },
    filters.reminderDueOnly === 'true' && { key: 'reminderDueOnly', label: '확인 시점 지난 공고만' },
    filters.supplementalOnly === 'true' && { key: 'supplementalOnly', label: '추가모집만' },
    filters.sort === 'DEADLINE' && { key: 'sort', label: '마감 임박순' },
  ].filter(Boolean) as Array<{ key: string; label: string }>

  if (!chips.length) return null
  return <section className="appliedFilters" aria-label="적용된 검색 조건"><div><strong>적용 조건</strong><div>{chips.map(chip => <button key={chip.key} type="button" onClick={() => onRemove(chip.key)} aria-label={`${chip.label} 조건 해제`}>{chip.label}<span aria-hidden="true">×</span></button>)}</div></div><button className="clearFilters" type="button" onClick={onReset}>전체 해제</button></section>
}
