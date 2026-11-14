import type { CatalogFilters } from './catalogFilters'

const statusLabels: Record<string, string> = { OPEN: '진행 중', UPCOMING: '모집 예정', CLOSED: '마감', UNKNOWN: '상태 미확인' }

export function searchSummary(filters: CatalogFilters) {
  const region = filters.dong || filters.sigungu || filters.sido || filters.region
  const values = [
    region,
    filters.query && `‘${filters.query}’`,
    filters.housingType,
    filters.status && statusLabels[filters.status],
    filters.depositMaxWon && `보증금 ${Number(filters.depositMaxWon).toLocaleString('ko-KR')}원 이하`,
    filters.rentMaxWon && `월세 ${Number(filters.rentMaxWon).toLocaleString('ko-KR')}원 이하`,
    (filters.areaMin || filters.areaMax) && `전용면적 ${filters.areaMin || '0'}~${filters.areaMax || '제한 없음'}㎡`,
    filters.savedOnly === 'true' && '관심 저장',
    filters.reminderDueOnly === 'true' && '확인 시점 지남',
    filters.deadlineSoonOnly === 'true' && '마감 임박',
    filters.supplementalOnly === 'true' && '추가모집만',
    filters.sort === 'DEADLINE' && '마감 임박순',
  ].filter(Boolean)
  return values.length ? `${values.join(' · ')} 공고 검색` : '전체 공고 검색'
}

export async function copySearchLink() {
  const value = window.location.href
  if (navigator.clipboard?.writeText && window.isSecureContext) { await navigator.clipboard.writeText(value); return }
  const textarea = document.createElement('textarea')
  textarea.value = value; textarea.setAttribute('readonly', ''); textarea.style.position = 'fixed'; textarea.style.opacity = '0'
  document.body.append(textarea); textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('링크를 복사하지 못했습니다.')
}
