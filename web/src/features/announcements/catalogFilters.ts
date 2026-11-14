export type CatalogFilters = Record<string, string>

export const SAVED_STORAGE_KEY = 'housing-monitor-saved-announcements-v1'
export const SAVED_NOTES_STORAGE_KEY = 'housing-monitor-saved-announcement-notes-v1'
export const SAVED_REMINDERS_STORAGE_KEY = 'housing-monitor-saved-announcement-reminders-v1'
export const SAVED_SEARCHES_STORAGE_KEY = 'housing-monitor-saved-searches-v1'
export type SavedSearch = { id: string; name: string; filters: CatalogFilters; createdAt: string; updatedAt: string }

export function defaultCatalogFilters(): CatalogFilters {
  return { query: '', region: '', housingType: '', source: '', status: '', depositMaxWon: '', rentMaxWon: '', areaMin: '', areaMax: '', includeUnknown: 'true', includeClosed: 'false', deadlineSoonOnly: 'false', interestOnly: 'false', savedOnly: 'false', reminderDueOnly: 'false', supplementalOnly: 'false', sort: 'LATEST' }
}

export function filtersFromSearch(search: URLSearchParams): CatalogFilters {
  return { ...defaultCatalogFilters(), ...Object.fromEntries(search.entries()) }
}

export function searchFromFilters(filters: CatalogFilters): URLSearchParams {
  return new URLSearchParams(Object.entries(filters).filter(([, value]) => value !== '' && !(value === 'false')))
}

const SAVED_SEARCH_FILTER_KEYS = ['query', 'region', 'sido', 'sigungu', 'dong', 'housingType', 'source', 'status', 'depositMaxWon', 'rentMaxWon', 'areaMin', 'areaMax', 'includeUnknown', 'includeClosed', 'deadlineSoonOnly', 'supplementalOnly', 'sort']

export function savedSearchFilters(filters: CatalogFilters): CatalogFilters {
  const next = defaultCatalogFilters()
  for (const key of SAVED_SEARCH_FILTER_KEYS) if (typeof filters[key] === 'string') next[key] = filters[key]
  next.savedOnly = 'false'; next.reminderDueOnly = 'false'; next.interestOnly = 'false'
  return next
}

export function savedSearches(storage: Storage = localStorage): SavedSearch[] {
  try {
    const value = JSON.parse(storage.getItem(SAVED_SEARCHES_STORAGE_KEY) || '[]')
    if (!Array.isArray(value)) return []
    return value.filter(item => item && typeof item.id === 'string' && typeof item.name === 'string' && item.name.trim()).slice(0, 20).map(item => ({ id: item.id, name: item.name.trim().slice(0, 30), filters: savedSearchFilters(item.filters || {}), createdAt: String(item.createdAt || ''), updatedAt: String(item.updatedAt || '') }))
  } catch { return [] }
}

export function saveSearch(name: string, filters: CatalogFilters, existingId?: string, storage: Storage = localStorage): SavedSearch[] {
  const normalized = name.trim().slice(0, 30); if (!normalized) throw new Error('검색 조건 이름을 입력해 주세요.')
  const current = savedSearches(storage); const now = new Date().toISOString(); const index = existingId ? current.findIndex(item => item.id === existingId) : -1
  if (index < 0 && current.length >= 20) throw new Error('저장 검색은 최대 20개까지 만들 수 있습니다.')
  const item: SavedSearch = { id: index >= 0 ? current[index].id : `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, name: normalized, filters: savedSearchFilters(filters), createdAt: index >= 0 ? current[index].createdAt : now, updatedAt: now }
  const next = index >= 0 ? current.map((value, position) => position === index ? item : value) : [item, ...current]
  storage.setItem(SAVED_SEARCHES_STORAGE_KEY, JSON.stringify(next)); return next
}

export function deleteSearch(id: string, storage: Storage = localStorage): SavedSearch[] {
  const next = savedSearches(storage).filter(item => item.id !== id); storage.setItem(SAVED_SEARCHES_STORAGE_KEY, JSON.stringify(next)); return next
}

export function savedAnnouncementIds(storage: Storage = localStorage): string[] {
  try { const value = JSON.parse(storage.getItem(SAVED_STORAGE_KEY) || '[]'); return Array.isArray(value) ? value.filter(item => typeof item === 'string' && item.trim()) : [] } catch { return [] }
}

export function toggleSavedAnnouncementId(id: string, storage: Storage = localStorage): string[] {
  const ids = savedAnnouncementIds(storage)
  const next = ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id]
  storage.setItem(SAVED_STORAGE_KEY, JSON.stringify(next))
  if (ids.includes(id)) {
    const notes = savedAnnouncementNotes(storage)
    if (Object.hasOwn(notes, id)) { delete notes[id]; storage.setItem(SAVED_NOTES_STORAGE_KEY, JSON.stringify(notes)) }
    const reminders = savedAnnouncementReminders(storage)
    if (Object.hasOwn(reminders, id)) { delete reminders[id]; storage.setItem(SAVED_REMINDERS_STORAGE_KEY, JSON.stringify(reminders)) }
  }
  return next
}

export function moveSavedAnnouncementId(id: string, direction: 'up' | 'down', storage: Storage = localStorage): string[] {
  const ids = savedAnnouncementIds(storage)
  const index = ids.indexOf(id); const target = direction === 'up' ? index - 1 : index + 1
  if (index < 0 || target < 0 || target >= ids.length) return ids
  const next = [...ids]; [next[index], next[target]] = [next[target], next[index]]
  storage.setItem(SAVED_STORAGE_KEY, JSON.stringify(next))
  return next
}

export function savedAnnouncementNotes(storage: Storage = localStorage): Record<string, string> {
  try {
    const value = JSON.parse(storage.getItem(SAVED_NOTES_STORAGE_KEY) || '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(Object.entries(value).filter(([id, note]) => typeof id === 'string' && typeof note === 'string' && note.trim()).map(([id, note]) => [id, String(note).slice(0, 300)]))
  } catch { return {} }
}

export function saveSavedAnnouncementNote(id: string, note: string, storage: Storage = localStorage): Record<string, string> {
  const next = savedAnnouncementNotes(storage); const value = note.trim().slice(0, 300)
  if (value) next[id] = value
  else delete next[id]
  storage.setItem(SAVED_NOTES_STORAGE_KEY, JSON.stringify(next))
  return next
}

export function savedAnnouncementReminders(storage: Storage = localStorage): Record<string, string> {
  try {
    const value = JSON.parse(storage.getItem(SAVED_REMINDERS_STORAGE_KEY) || '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(Object.entries(value).filter(([id, reminder]) => typeof id === 'string' && typeof reminder === 'string' && !Number.isNaN(new Date(reminder).getTime())).map(([id, reminder]) => [id, String(reminder)]))
  } catch { return {} }
}

export function saveSavedAnnouncementReminder(id: string, reminder: string, storage: Storage = localStorage): Record<string, string> {
  const next = savedAnnouncementReminders(storage)
  if (reminder && !Number.isNaN(new Date(reminder).getTime())) next[id] = reminder
  else delete next[id]
  storage.setItem(SAVED_REMINDERS_STORAGE_KEY, JSON.stringify(next))
  return next
}

export function dueSavedAnnouncementIds(ids: string[], reminders: Record<string, string>, now = Date.now()) {
  return ids.filter(id => { const value = reminders[id]; return Boolean(value) && new Date(value).getTime() <= now })
}

function profileRegions(profile: Record<string, unknown>) {
  const value = profile.preferredAreas
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0).map(item => item.trim())
  return typeof value === 'string' ? value.split(/[,\n]/).map(item => item.trim()).filter(Boolean) : []
}

function profileNumber(value: unknown) {
  const normalized = String(value ?? '').replace(/,/g, '').trim()
  return normalized && Number.isFinite(Number(normalized)) ? normalized : undefined
}

export function hasInterestCriteria(profile: Record<string, unknown> = {}) {
  return profileRegions(profile).length > 0 || [profile.maxDeposit, profile.maxMonthlyRent, profile.minExclusiveArea, profile.maxExclusiveArea].some(value => Boolean(profileNumber(value)))
}

export function catalogParams(filters: CatalogFilters, page: number, profile: Record<string, unknown> = {}, savedIds = savedAnnouncementIds(), savedReminders = savedAnnouncementReminders()): string {
  const { sido: _sido, sigungu: _sigungu, dong: _dong, reminderDueOnly: _reminderDueOnly, ...apiFilters } = filters
  const params = new URLSearchParams({ page: String(page), size: '10', ...apiFilters, sort: filters.savedOnly === 'true' ? 'SAVED' : filters.sort })
  if (filters.savedOnly === 'true') (filters.reminderDueOnly === 'true' ? dueSavedAnnouncementIds(savedIds, savedReminders) : savedIds).forEach(id => params.append('savedId', id))
  if (filters.interestOnly === 'true') {
    profileRegions(profile).forEach(region => params.append('interestRegion', region))
    const interestValues: Array<[string, unknown]> = [['interestDepositMaxWon', profile.maxDeposit], ['interestRentMaxWon', profile.maxMonthlyRent], ['interestAreaMin', profile.minExclusiveArea], ['interestAreaMax', profile.maxExclusiveArea]]
    for (const [key, value] of interestValues) {
      const normalized = profileNumber(value)
      if (normalized) params.set(key, normalized)
    }
    params.set('interestIncludeUnknown', 'true')
  }
  return params.toString()
}
