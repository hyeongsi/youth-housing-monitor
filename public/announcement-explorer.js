export const MISSING_LIST_VALUES = new Set(['', '목록 기재 없음', '목록 미기재']);

export function hasKnownText(value) {
  return value != null && !MISSING_LIST_VALUES.has(String(value).trim());
}

export function verifiedOptions(detail) {
  return Array.isArray(detail?.costOptions)
    ? detail.costOptions.filter((option) => Number.isFinite(option?.depositWon) && Number.isFinite(option?.monthlyRentWon))
    : [];
}

export function explicitLocation(item, detail) {
  const detailed = [detail?.location?.district, detail?.location?.address].filter(hasKnownText).join(' ');
  return detailed || (hasKnownText(item?.area) ? String(item.area).trim() : null);
}

export function areaSquareMeters(detail) {
  const match = String(detail?.exclusiveAreaText || '').match(/(\d+(?:\.\d+)?)\s*㎡/);
  return match ? Number(match[1]) : null;
}

export function searchMatches(item, detail, query) {
  const terms = String(query || '').toLocaleLowerCase('ko').trim().split(/\s+/).filter(Boolean);
  if (!terms.length) return true;
  const searchable = [item?.title, item?.sourceName, item?.provider, item?.housingType, item?.area, explicitLocation(item, detail)]
    .filter(hasKnownText).join(' ').toLocaleLowerCase('ko');
  return terms.every((term) => searchable.includes(term));
}

export function classifyInterest(item, detail, preferences) {
  const regions = (preferences.regions || []).map((value) => value.toLocaleLowerCase('ko')).filter(Boolean);
  const location = explicitLocation(item, detail);
  const regionMatch = regions.length > 0 && location && regions.some((region) => location.toLocaleLowerCase('ko').includes(region));
  const options = verifiedOptions(detail);
  const area = areaSquareMeters(detail);
  const needsLocation = !location;
  const needsCosts = !options.length;
  const needsArea = area === null;
  const hasCriteria = regions.length > 0 || Number.isFinite(preferences.depositMaxWon) || Number.isFinite(preferences.monthlyRentMaxWon)
    || Number.isFinite(preferences.areaMin) || Number.isFinite(preferences.areaMax);
  const matchingOptions = options.filter((option) =>
    (!Number.isFinite(preferences.depositMaxWon) || option.depositWon <= preferences.depositMaxWon)
    && (!Number.isFinite(preferences.monthlyRentMaxWon) || option.monthlyRentWon <= preferences.monthlyRentMaxWon));
  const pairMatches = matchingOptions.length > 0;
  const areaMatches = area !== null
    && (!Number.isFinite(preferences.areaMin) || area >= preferences.areaMin)
    && (!Number.isFinite(preferences.areaMax) || area <= preferences.areaMax);
  const reasons = [];
  if (regionMatch) reasons.push(`${location} 일치`);
  if (pairMatches && Number.isFinite(preferences.depositMaxWon)) reasons.push('보증금 기준 안');
  if (pairMatches && Number.isFinite(preferences.monthlyRentMaxWon)) reasons.push('월세 기준 안');
  if (areaMatches && (Number.isFinite(preferences.areaMin) || Number.isFinite(preferences.areaMax))) reasons.push('면적 기준 안');
  const unknown = [];
  if (needsLocation) unknown.push('위치 확인 필요');
  if (needsCosts) unknown.push('보증금·월세 확인 필요');
  if (needsArea) unknown.push('전용면적 확인 필요');

  if (!hasCriteria) return { kind: 'NONE', reasons: [], unknown, location, options, matchingOptions, area };
  const knownCriteriaMismatch = (!needsCosts && !pairMatches) || (!needsArea && !areaMatches);
  if (knownCriteriaMismatch) return { kind: 'NONE', reasons: [], unknown, location, options, matchingOptions, area };
  if (regionMatch && !unknown.length && pairMatches && areaMatches) {
    return { kind: 'COMPARABLE', reasons, unknown, location, options, matchingOptions, area, eligibilityNotice: true };
  }
  if ((regionMatch || (needsLocation && regions.length > 0)) && unknown.length) {
    return { kind: 'NEEDS_INFO', reasons: reasons.length ? reasons : ['조건과 비교 전 정보 확인 필요'], unknown, location, options, matchingOptions, area };
  }
  return { kind: 'NONE', reasons: [], unknown, location, options, matchingOptions, area };
}
