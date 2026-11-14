import type { RecordValue } from '../../api'
import type { CatalogFilters } from './catalogFilters'

const number = (value: unknown) => Number.isFinite(Number(value)) ? Number(value) : undefined
const wordsMatch = (value: unknown, query: string) => query.trim().split(/\s+/).filter(Boolean).every(word => String(value || '').includes(word))

function sites(item: RecordValue) { return Array.isArray(item.detail?.sites) ? item.detail.sites as RecordValue[] : [] }
function units(item: RecordValue) { return sites(item).flatMap(site => Array.isArray(site.units) ? site.units as RecordValue[] : []) }
function locationText(item: RecordValue) { return [item.region, item.address, item.area, ...sites(item).flatMap(site => Object.values(site.location || {}))].filter(Boolean).join(' ') }

export function matchReasons(item: RecordValue, filters: CatalogFilters): string[] {
  const reasons: string[] = []
  const region = filters.region || filters.dong || filters.sigungu || filters.sido
  if (filters.query && wordsMatch(item.title, filters.query)) reasons.push('검색어 일치')
  if (region && locationText(item).includes(region)) reasons.push('지역 조건 충족')

  const minArea = number(filters.areaMin); const maxArea = number(filters.areaMax)
  if ((minArea !== undefined || maxArea !== undefined) && units(item).some(unit => {
    const area = number(unit.exclusiveAreaM2)
    return area !== undefined && (minArea === undefined || area >= minArea) && (maxArea === undefined || area <= maxArea)
  })) reasons.push('전용면적 조건 충족')

  const maxDeposit = number(filters.depositMaxWon); const maxRent = number(filters.rentMaxWon)
  if ((maxDeposit !== undefined || maxRent !== undefined) && units(item).some(unit => (unit.rentalOptions || []).some((option: RecordValue) => {
    const deposit = number(option.depositWon); const rent = number(option.monthlyRentWon)
    return (maxDeposit === undefined || (deposit !== undefined && deposit <= maxDeposit)) && (maxRent === undefined || (rent !== undefined && rent <= maxRent))
  }))) reasons.push('임대조건 충족')

  if (filters.supplementalOnly === 'true' && `${item.title || ''} ${item.status || ''}`.includes('추가모집')) reasons.push('추가모집 공고')
  return reasons
}
