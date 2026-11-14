import { Button, EmptyState } from '../../components/ui'
import { Link } from 'react-router-dom'
import type { CatalogFilters } from './catalogFilters'

type Props = { filters: CatalogFilters; savedCount: number; onRelax: (key: string) => void; onReset: () => void }

export function EmptySearchGuidance({ filters, savedCount, onRelax, onReset }: Props) {
  if (filters.savedOnly === 'true' && savedCount === 0) return <section className="emptyGuidance"><EmptyState>아직 관심 저장한 공고가 없습니다.</EmptyState><p>공고 찾기에서 마음에 드는 공고의 ‘관심 저장’ 버튼을 누르면 이곳에서 다시 확인할 수 있습니다.</p><Link className="emptyAction" to="/announcements">공고 찾기로 이동</Link></section>
  if (filters.deadlineSoonOnly === 'true') return <section className="emptyGuidance"><EmptyState>마감이 임박한 공고가 없습니다.</EmptyState><p>오늘부터 3일 이내에 접수가 마감되는 공고만 표시합니다.</p><Button tone="secondary" onClick={() => onRelax('deadlineSoonOnly')}>마감 임박 필터 해제</Button></section>
  if (filters.reminderDueOnly === 'true') return <section className="emptyGuidance"><EmptyState>확인 시점이 지난 관심 저장 공고가 없습니다.</EmptyState><p>알림 시점을 설정한 공고는 저장 목록에서 이 조건으로 다시 확인할 수 있습니다.</p><Button tone="secondary" onClick={() => onRelax('reminderDueOnly')}>알림 필터 해제</Button></section>
  const suggestions = [
    filters.includeUnknown === 'false' && ['includeUnknown', '정보 미확인 공고 포함'],
    filters.includeClosed !== 'true' && ['includeClosed', '마감 공고 포함'],
    filters.supplementalOnly === 'true' && ['supplementalOnly', '추가모집 조건 해제'],
    filters.savedOnly === 'true' && ['savedOnly', '관심 저장 조건 해제'],
    filters.interestOnly === 'true' && ['interestOnly', '관심 조건 일치 해제'],
    filters.status && ['status', '모집 상태 조건 해제'],
    (filters.region || filters.sido || filters.sigungu || filters.dong) && ['region', '지역 조건 해제'],
    (filters.areaMin || filters.areaMax) && ['area', '전용면적 조건 해제'],
    (filters.depositMaxWon || filters.rentMaxWon) && ['cost', '금액 조건 해제'],
  ].filter(Boolean).slice(0, 3) as Array<[string, string]>

  if (!suggestions.length) return <EmptyState>현재 조건에 맞는 공고가 없습니다. 수집된 공고가 늘어나면 이곳에 표시됩니다.</EmptyState>
  return <section className="emptyGuidance"><EmptyState>현재 조건에 맞는 공고가 없습니다.</EmptyState><p>아래 조건을 완화해 다시 찾아볼 수 있습니다.</p><div>{suggestions.map(([key, label]) => <Button key={key} tone="secondary" onClick={() => onRelax(key)}>{label}</Button>)}<Button tone="text" onClick={onReset}>조건 전체 초기화</Button></div></section>
}
