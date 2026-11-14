export const emptySite = { sourceSiteKey: 'manual-site', name: '', address: '', locationGuide: '', totalHouseholds: null, supplyCount: null, organizations: [], units: [] }
export const emptyUnit = { sourceUnitKey: 'manual-unit', categoryText: '', unitType: '', exclusiveAreaM2: null, supplyCount: null, rooms: [], rentalOptions: [] }
export const emptyRoom = { buildingText: '', floorText: '', roomText: '', sourceText: '' }
export const emptyRentalOption = { optionKey: 'manual-option', conditionText: '', depositRawText: '', depositRawUnit: '원', depositWon: null, depositUnitConfirmed: false, monthlyRentRawText: '', monthlyRentRawUnit: '원', monthlyRentWon: null, monthlyRentUnitConfirmed: false, managementFeeRawText: '', managementFeeWon: null, conversionConditionText: '' }
export const emptyEvent = { eventType: 'APPLICATION', startsAt: '', endsAt: '', status: 'CONFIRMED', description: '' }
export const emptyEligibilityRule = { category: 'OTHER', targetGroup: '', rank: null, householdSize: null, operator: '', thresholdValue: null, thresholdUnit: '', description: '' }

const keys = (path: string) => path.replaceAll(']', '').split(/[.[]/).filter(Boolean)

export const valueAtPath = (root: any, path: string) => keys(path).reduce((value, key) => value?.[Number.isNaN(Number(key)) ? key : Number(key)], root)

export function setValueAtPath(root: any, path: string, value: unknown) {
  const segments = keys(path)
  const last = segments.pop()!
  const target = segments.reduce((current, key) => current[Number.isNaN(Number(key)) ? key : Number(key)], root)
  target[Number.isNaN(Number(last)) ? last : Number(last)] = value
}
