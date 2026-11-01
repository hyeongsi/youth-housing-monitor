import { areaSquareMeters, classifyInterest, explicitLocation, searchMatches as explorerSearchMatches, verifiedOptions } from './announcement-explorer.js';

const $ = (selector, root = document) => root.querySelector(selector);
const ANNOUNCEMENT_PAGE_SIZE = 10;
const HOME_PREVIEW_SIZE = 3;
const PROGRAM_PREVIEW_SIZE = 6;
const MISSING_LIST_VALUE = '목록 미기재';
const NEEDS_NOTICE = '공고문 확인 필요';
const INTEREST_STORAGE_KEY = 'housing-monitor-interest-preferences-v1';
const SAVED_STORAGE_KEY = 'housing-monitor-saved-announcements-v1';

let state;
let announcementLimit = ANNOUNCEMENT_PAGE_SIZE;
let reloadInFlight = false;
let toastTimer;
let drawerTrigger = null;
let interestPreferences = { regions: [], depositMaxWon: null, monthlyRentMaxWon: null, areaMin: null, areaMax: null, includeUnknown: true };
let savedAnnouncementIds = new Set();
function createDefaultCatalogFilters() {
  return { source: '', status: '', information: '', newOnly: false, includeUnknown: true, query: '', region: '', housingType: '', depositMaxWon: '', monthlyRentMaxWon: '', areaMin: '', areaMax: '' };
}
const catalogState = { draftFilters: createDefaultCatalogFilters(), appliedFilters: createDefaultCatalogFilters() };

function hasText(value) {
  return value != null && String(value).trim() !== '';
}

function digitsOnly(value) {
  return String(value ?? '').replace(/\D/g, '');
}

function formatNumericValue(value) {
  const digits = digitsOnly(value);
  return digits ? Number(digits).toLocaleString('ko-KR') : '';
}

function parseNumericValue(value) {
  return digitsOnly(value);
}

function formatNumericInput(input) {
  const original = input.value;
  const caret = input.selectionStart ?? original.length;
  const digitsBeforeCaret = digitsOnly(original.slice(0, caret)).length;
  const formatted = original.includes('-') ? '' : formatNumericValue(original);
  input.value = formatted;
  let nextCaret = formatted.length;
  if (digitsBeforeCaret) {
    let seen = 0;
    for (let index = 0; index < formatted.length; index += 1) {
      if (/\d/.test(formatted[index])) seen += 1;
      if (seen === digitsBeforeCaret) { nextCaret = index + 1; break; }
    }
  }
  input.setSelectionRange(nextCaret, nextCaret);
}

function setAreaValidation() {
  const minimum = numberOrNull($('#interest-area-min').value);
  const maximum = numberOrNull($('#interest-area-max').value);
  const error = $('#interest-area-error');
  const invalid = minimum !== null && maximum !== null && minimum > maximum;
  error.hidden = !invalid;
  error.textContent = invalid ? '전용면적 최소는 최대보다 클 수 없습니다.' : '';
  $('#interest-area-min').setAttribute('aria-invalid', String(invalid));
  $('#interest-area-max').setAttribute('aria-invalid', String(invalid));
  return !invalid;
}

function isListMissing(value) {
  return !hasText(value) || String(value).trim() === '목록 기재 없음' || String(value).trim() === MISSING_LIST_VALUE;
}

function listValue(value) {
  return isListMissing(value) ? MISSING_LIST_VALUE : String(value);
}

function formatDate(value) {
  return hasText(value) ? String(value).replace('T', ' ') : '미확인';
}

function formatHeaderDate(value) {
  if (!hasText(value)) return '최근 수집 기록 없음';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return `최근 수집 ${formatDate(value)}`;
  return `최근 수집 ${new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)}`;
}

function shortDate(value) {
  if (!hasText(value)) return '미확인';
  const matched = String(value).match(/(\d{4})-(\d{2})-(\d{2})/);
  return matched ? `${Number(matched[2])}/${Number(matched[3])}` : formatDate(value);
}

function formatWon(value) {
  if (!Number.isFinite(value) || value < 0) return null;
  const man = value / 10000;
  if (man >= 10000) {
    const eok = Math.floor(man / 10000);
    const remainder = man % 10000;
    return remainder ? `${eok}억 ${remainder.toLocaleString('ko-KR')}만 원` : `${eok}억 원`;
  }
  return `${man.toLocaleString('ko-KR')}만 원`;
}

function verifiedDetailFor(item) {
  const details = state.announcementDetails?.items || {};
  const detail = details[item.id];
  return detail?.verificationStatus === 'VERIFIED' ? detail : null;
}

function verifiedCostOptions(detail) {
  return Array.isArray(detail?.costOptions) ? detail.costOptions.filter((option) => option && (Number.isFinite(option.depositWon) || Number.isFinite(option.monthlyRentWon))) : [];
}

function safeLocation(detail, item) {
  const values = [detail?.location?.district, detail?.location?.address].filter(hasText);
  return values.length ? values.join(' ') : listValue(item?.area);
}

function listLocation(item, detail) {
  const detailed = detail ? safeLocation(detail, item) : null;
  return detailed && detailed !== MISSING_LIST_VALUE ? detailed : listValue(item.area);
}

function exclusiveArea(detail) {
  return hasText(detail?.exclusiveAreaText) ? detail.exclusiveAreaText : NEEDS_NOTICE;
}

function applicationSchedule(item, detail) {
  const application = detail?.application;
  if (application?.start || application?.end) return `${shortDate(application.start)} ~ ${shortDate(application.end)}`;
  const start = isListMissing(item.applicationStart) ? null : item.applicationStart;
  const end = isListMissing(item.applicationEnd) ? null : item.applicationEnd;
  return start || end ? `${start ? formatDate(start) : '시작 미기재'} ~ ${end ? formatDate(end) : '마감 미기재'}` : NEEDS_NOTICE;
}

function deadlineForList(item, detail) {
  // Preserve a timestamp supplied by the collection source before using detail data.
  if (!isListMissing(item.applicationEnd)) return formatDate(item.applicationEnd);
  if (detail?.application?.end) return formatDate(detail.application.end);
  return MISSING_LIST_VALUE;
}

function costForList(detail, field) {
  if (!detail) return NEEDS_NOTICE;
  const options = verifiedCostOptions(detail);
  if (options.length !== 1) return '주택형·조건별 상이 · 공고문 확인';
  return formatWon(options[0][field]) || NEEDS_NOTICE;
}

function newAnnouncementIds() {
  return new Set((state.discoveries || []).map((item) => item.id).filter(Boolean));
}

function newItemsBaselineAt() {
  return state.notifications?.newItemsBaselineAt || null;
}

function fact(container, label, value) {
  const group = document.createElement('div');
  const term = document.createElement('dt');
  const description = document.createElement('dd');
  term.textContent = label;
  description.textContent = value;
  group.append(term, description);
  container.append(group);
}

function setOfficialLink(link, url, text, labelPrefix = '') {
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  link.textContent = text;
  link.setAttribute('aria-label', `${labelPrefix ? `${labelPrefix} ` : ''}${text} (새 탭에서 열림)`);
}

function appendCostOptions(card, detail) {
  const options = verifiedCostOptions(detail);
  if (!options.length) return;
  const group = document.createElement('div');
  group.className = 'cost-options';
  const heading = document.createElement('p');
  heading.className = 'cost-options-heading';
  heading.textContent = '주택형·조건별 보증금 / 월세 조합';
  const list = document.createElement('ul');
  for (const option of options) {
    const item = document.createElement('li');
    const context = [option.typeText || option.housingType, option.conditionText].filter(hasText).join(' · ');
    const deposit = formatWon(option.depositWon) || NEEDS_NOTICE;
    const rent = formatWon(option.monthlyRentWon) || NEEDS_NOTICE;
    item.textContent = `${context || '공고 표 기재 조합'}: 보증금 ${deposit} / 월세 ${rent}`;
    list.append(item);
  }
  group.append(heading, list);
  card.append(group);
}

function drawerFact(container, label, value) {
  const item = document.createElement('div');
  item.className = 'drawer-fact';
  const heading = document.createElement('span');
  heading.textContent = label;
  const content = document.createElement('strong');
  content.textContent = value;
  item.append(heading, content);
  container.append(item);
}

function closeAnnouncementDrawer() {
  const drawer = $('#announcement-drawer');
  if (drawer.hidden) return;
  drawer.hidden = true;
  $('#announcement-drawer-backdrop').hidden = true;
  $('#announcement-drawer-actions').replaceChildren();
  document.body.classList.remove('drawer-open');
  drawerTrigger?.focus();
  drawerTrigger = null;
}

function trapDrawerFocus(event) {
  const drawer = $('#announcement-drawer');
  if (drawer.hidden || event.key !== 'Tab') return;
  const focusable = [...drawer.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex="-1"])')];
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function openAnnouncementDrawer(item, detail, trigger) {
  drawerTrigger = trigger;
  const content = $('#announcement-drawer-content');
  content.replaceChildren();
  const status = document.createElement('p');
  status.className = 'drawer-status';
  status.textContent = listValue(item.status);
  const title = document.createElement('h2');
  title.id = 'announcement-drawer-title';
  title.textContent = listValue(item.title);
  const source = document.createElement('p');
  source.className = 'muted';
  source.textContent = `${listValue(item.sourceName)} · ${listValue(item.housingType)}`;
  const facts = document.createElement('div');
  facts.className = 'drawer-facts';
  drawerFact(facts, '출처', listValue(item.sourceName));
  drawerFact(facts, '공급기관', listValue(item.provider));
  drawerFact(facts, '주택 유형', listValue(item.housingType));
  drawerFact(facts, '모집 상태', listValue(item.status));
  drawerFact(facts, '공고일', isListMissing(item.announcementDate) ? MISSING_LIST_VALUE : formatDate(item.announcementDate));
  drawerFact(facts, '접수 마감', deadlineForList(item, detail));
  drawerFact(facts, '지역', listLocation(item, detail));
  if (detail) {
    const options = verifiedCostOptions(detail);
    if (options.length === 1) {
      drawerFact(facts, '보증금', formatWon(options[0].depositWon) || NEEDS_NOTICE);
      drawerFact(facts, '월세', formatWon(options[0].monthlyRentWon) || NEEDS_NOTICE);
    }
    drawerFact(facts, '전용면적', exclusiveArea(detail));
  } else {
    drawerFact(facts, '보증금', NEEDS_NOTICE);
    drawerFact(facts, '월세', NEEDS_NOTICE);
    drawerFact(facts, '전용면적', NEEDS_NOTICE);
  }
  const note = document.createElement('p');
  note.className = 'drawer-note';
  note.textContent = detail
    ? '신청 자격과 세부 조건은 공식 공고문에서 별도로 확인하세요.'
    : '비용·전용면적 등 상세 조건은 목록에 확인되지 않았습니다. 공고문에서 상세 확인 필요';
  content.append(status, title, source, facts);
  if (detail) appendCostOptions(content, detail);
  content.append(note);
  const officialUrl = detail?.sourceUrl || item.officialUrl;
  const actions = $('#announcement-drawer-actions');
  actions.replaceChildren();
  if (hasText(officialUrl)) {
    const link = document.createElement('a');
    link.className = 'official drawer-official';
    setOfficialLink(link, officialUrl, '공식 공고문 보기', listValue(item.title));
    actions.append(link);
  }
  $('#announcement-drawer-backdrop').hidden = false;
  const drawer = $('#announcement-drawer');
  drawer.hidden = false;
  document.body.classList.add('drawer-open');
  $('#announcement-drawer-close').focus();
}

function drawerButton(item, detail) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'secondary open-announcement-detail';
  button.textContent = '상세 보기';
  button.setAttribute('aria-label', `${listValue(item.title)} 상세 보기`);
  button.addEventListener('click', () => openAnnouncementDrawer(item, detail, button));
  return button;
}

function renderVerifiedAnnouncements() {
  const target = $('#notices');
  const joined = Array.isArray(state.verifiedAnnouncements) ? state.verifiedAnnouncements : [];
  $('#verified-announcement-count').textContent = `${joined.length.toLocaleString('ko-KR')}건`;
  target.replaceChildren();

  if (!joined.length) {
    const message = document.createElement('p');
    message.className = 'muted empty-state';
    message.textContent = '검증일과 공식 원문 링크를 함께 가진 상세 확인 공고가 아직 없습니다.';
    target.append(message);
    return;
  }

  for (const joinedItem of joined.slice(0, HOME_PREVIEW_SIZE)) {
    const item = joinedItem.announcement || {};
    const detail = joinedItem.detail || {};
    const card = document.createElement('article');
    card.className = 'notice-card verified-card';

    const sourceStatus = document.createElement('p');
    sourceStatus.className = 'source-status';
    sourceStatus.textContent = `출처: ${listValue(item.sourceName)} · 목록 상태: ${listValue(item.status)}`;

    const title = document.createElement('h3');
    title.textContent = listValue(item.title);

    const facts = document.createElement('dl');
    facts.className = 'notice-facts';
    const options = verifiedCostOptions(detail);
    const singleOption = options.length === 1 ? options[0] : null;
    fact(facts, '지역', safeLocation(detail, item));
    fact(facts, '보증금', singleOption ? (formatWon(singleOption.depositWon) || NEEDS_NOTICE) : '주택형·조건별 상이 · 공고문 확인');
    fact(facts, '월세', singleOption ? (formatWon(singleOption.monthlyRentWon) || NEEDS_NOTICE) : '주택형·조건별 상이 · 공고문 확인');
    fact(facts, '전용면적', exclusiveArea(detail));

    const schedule = document.createElement('p');
    schedule.className = 'schedule';
    schedule.textContent = `접수 일정: ${applicationSchedule(item, detail)}`;
    const moveIn = document.createElement('p');
    moveIn.className = 'move-in';
    moveIn.textContent = `입주 정보: ${hasText(detail.moveInText) ? detail.moveInText : NEEDS_NOTICE}`;
    const verified = document.createElement('p');
    verified.className = 'verified-at';
    verified.textContent = `검증일: ${formatDate(detail.verifiedAt)}`;
    const link = document.createElement('a');
    link.className = 'official';
    setOfficialLink(link, detail.sourceUrl, '공식 공고문 보기', listValue(item.title));

    card.append(sourceStatus, title, facts);
    appendCostOptions(card, detail);
    card.append(schedule, moveIn, verified, drawerButton(item, detail), link);
    target.append(card);
  }
}

function optionValues(items, field) {
  return [...new Set(items.map((item) => item[field]).filter((value) => !isListMissing(value)))].sort((a, b) => String(a).localeCompare(String(b), 'ko'));
}

function regionOptionValues(items) {
  return [...new Set(items.map((item) => listValue(item.area)).filter((value) => value !== MISSING_LIST_VALUE))]
    .sort((a, b) => String(a).localeCompare(String(b), 'ko'));
}

function setSelectOptions(selector, values) {
  const select = $(selector);
  const current = select.value;
  const labels = { 'announcement-source': '모든 출처', 'announcement-status': '모든 상태', 'announcement-region': '모든 지역', 'announcement-housing-type': '모든 주택 유형' };
  select.replaceChildren(new Option(labels[select.id] || '전체', ''));
  for (const value of values) select.add(new Option(value, value));
  select.value = values.includes(current) ? current : '';
}

function informationStatus(item, detail) {
  return explicitLocation(item, detail) && verifiedOptions(detail).length && areaSquareMeters(detail) !== null ? 'known' : 'needs-info';
}

function missingInfoLabels(item, detail) {
  const missing = [];
  if (!explicitLocation(item, detail)) missing.push('위치');
  if (!verifiedOptions(detail).length) missing.push('보증금·월세');
  if (areaSquareMeters(detail) === null) missing.push('전용면적');
  return missing;
}

function announcementInfoText(item, detail) {
  const missing = missingInfoLabels(item, detail);
  return missing.length ? `${missing.join(' · ')} 확인 필요` : '값 확인됨 · 자격 별도 확인';
}

function filteredAnnouncements() {
  const catalog = state.allAnnouncements || { items: [] };
  const { source, status, information, newOnly, includeUnknown, query, region, housingType, depositMaxWon, monthlyRentMaxWon, areaMin, areaMax } = catalogState.appliedFilters;
  const newIds = newAnnouncementIds();
  return (catalog.items || []).filter((item) => {
    const detail = verifiedDetailFor(item);
    const options = verifiedOptions(detail);
    const area = areaSquareMeters(detail);
    const numericFilters = [depositMaxWon, monthlyRentMaxWon, areaMin, areaMax].some(hasText);
    const optionMatches = options.some((option) =>
      (!hasText(depositMaxWon) || Number.isFinite(option.depositWon) && option.depositWon <= Number(depositMaxWon))
      && (!hasText(monthlyRentMaxWon) || Number.isFinite(option.monthlyRentWon) && option.monthlyRentWon <= Number(monthlyRentMaxWon))
    );
    const areaMatches = (!hasText(areaMin) || area !== null && area >= Number(areaMin))
      && (!hasText(areaMax) || area !== null && area <= Number(areaMax));
    const hasComparableValues = options.length > 0 && area !== null;
    const numericMatches = !numericFilters || (hasComparableValues ? optionMatches && areaMatches : includeUnknown);
    const location = listLocation(item, detail);
    const regionMatches = !region || (location !== MISSING_LIST_VALUE && location.includes(region));
    return (!source || item.sourceName === source)
      && (!status || item.status === status)
      && (!housingType || item.housingType === housingType)
      && regionMatches
      && (!information || informationStatus(item, detail) === information)
      && (!newOnly || newIds.has(item.id))
      && numericMatches
      && explorerSearchMatches(item, detail, query);
  });
}

function syncDraftFilters() {
  catalogState.draftFilters = {
    source: $('#announcement-source').value,
    status: $('#announcement-status').value,
    information: $('#announcement-information').value,
    newOnly: $('#announcement-new-only').checked,
    includeUnknown: $('#announcement-include-unknown').checked,
    query: $('#announcement-query').value.trim(),
    region: $('#announcement-region').value,
    housingType: $('#announcement-housing-type').value,
    depositMaxWon: parseNumericValue($('#announcement-deposit-max').value),
    monthlyRentMaxWon: parseNumericValue($('#announcement-rent-max').value),
    areaMin: parseNumericValue($('#announcement-area-min').value),
    areaMax: parseNumericValue($('#announcement-area-max').value),
  };
}

function writeDraftFilters() {
  const filters = catalogState.draftFilters;
  $('#announcement-source').value = filters.source;
  $('#announcement-status').value = filters.status;
  $('#announcement-information').value = filters.information;
  $('#announcement-new-only').checked = filters.newOnly;
  $('#announcement-include-unknown').checked = filters.includeUnknown;
  $('#announcement-query').value = filters.query;
  $('#announcement-region').value = filters.region;
  $('#announcement-housing-type').value = filters.housingType;
  $('#announcement-deposit-max').value = formatNumericValue(filters.depositMaxWon);
  $('#announcement-rent-max').value = formatNumericValue(filters.monthlyRentMaxWon);
  $('#announcement-area-min').value = formatNumericValue(filters.areaMin);
  $('#announcement-area-max').value = formatNumericValue(filters.areaMax);
}

function appliedFilterSummary() {
  const filters = catalogState.appliedFilters;
  const labels = [filters.query, filters.region, filters.housingType, filters.information === 'known' ? '값 확인됨' : filters.information === 'needs-info' ? '정보 확인 필요' : '', filters.source, filters.status, hasText(filters.depositMaxWon) ? `보증금 ${formatNumericValue(filters.depositMaxWon)}원 이하` : '', hasText(filters.monthlyRentMaxWon) ? `월세 ${formatNumericValue(filters.monthlyRentMaxWon)}원 이하` : '', filters.newOnly ? '신규' : ''].filter(Boolean);
  return labels.length ? `${labels.join(' · ')} 조건의 ` : '';
}

function numberOrNull(value) {
  if (!hasText(value)) return null;
  const digits = parseNumericValue(value);
  const number = Number(digits);
  return digits && Number.isSafeInteger(number) && number >= 0 ? number : null;
}

function loadInterestPreferences() {
  try {
    const stored = JSON.parse(localStorage.getItem(INTEREST_STORAGE_KEY));
    if (stored && typeof stored === 'object') interestPreferences = { ...interestPreferences, ...stored };
    const saved = JSON.parse(localStorage.getItem(SAVED_STORAGE_KEY));
    if (Array.isArray(saved)) savedAnnouncementIds = new Set(saved);
  } catch { /* Local storage is optional. */ }
  $('#interest-regions').value = interestPreferences.regions.join(', ');
  $('#interest-deposit-max').value = formatNumericValue(interestPreferences.depositMaxWon);
  $('#interest-rent-max').value = formatNumericValue(interestPreferences.monthlyRentMaxWon);
  $('#interest-area-min').value = formatNumericValue(interestPreferences.areaMin);
  $('#interest-area-max').value = formatNumericValue(interestPreferences.areaMax);
  $('#interest-include-unknown').checked = interestPreferences.includeUnknown !== false;
  setAreaValidation();
  renderInterestPreferenceSummary();
}

function renderInterestPreferenceSummary() {
  const parts = [
    interestPreferences.regions?.length ? `지역 ${interestPreferences.regions.join(', ')}` : '지역 미설정',
    interestPreferences.depositMaxWon != null ? `보증금 ${formatNumericValue(interestPreferences.depositMaxWon)}원 이하` : '',
    interestPreferences.monthlyRentMaxWon != null ? `월세 ${formatNumericValue(interestPreferences.monthlyRentMaxWon)}원 이하` : '',
    interestPreferences.areaMin != null || interestPreferences.areaMax != null ? `전용 ${interestPreferences.areaMin ?? '–'}~${interestPreferences.areaMax ?? '–'}㎡` : '',
  ].filter(Boolean);
  $('#interest-preference-summary').textContent = `현재 조건: ${parts.join(' · ')}${interestPreferences.includeUnknown === false ? ' · 정보 미기재 제외' : ''}`;
}

function saveInterestPreferences() {
  if (!setAreaValidation()) {
    $('#interest-area-min').focus();
    return false;
  }
  interestPreferences = {
    regions: $('#interest-regions').value.split(',').map((value) => value.trim()).filter(Boolean),
    depositMaxWon: numberOrNull($('#interest-deposit-max').value),
    monthlyRentMaxWon: numberOrNull($('#interest-rent-max').value),
    areaMin: numberOrNull($('#interest-area-min').value),
    areaMax: numberOrNull($('#interest-area-max').value),
    includeUnknown: $('#interest-include-unknown').checked,
  };
  try { localStorage.setItem(INTEREST_STORAGE_KEY, JSON.stringify(interestPreferences)); } catch { /* Local storage is optional. */ }
  $('#interest-save-status').textContent = '이 브라우저에 관심 조건을 적용하고 저장했습니다.';
  renderInterestPreferenceSummary();
  renderInterestCandidates();
  renderHomeSummary();
  showToast({ type: 'success', message: '관심 조건을 저장했습니다.' });
  return true;
}

function toggleSavedAnnouncement(id) {
  if (savedAnnouncementIds.has(id)) savedAnnouncementIds.delete(id);
  else savedAnnouncementIds.add(id);
  try { localStorage.setItem(SAVED_STORAGE_KEY, JSON.stringify([...savedAnnouncementIds])); } catch { /* Local storage is optional. */ }
  renderInterestCandidates();
  renderNewAnnouncements();
  invalidateAnnouncementRender();
  renderAllAnnouncements();
}

function favoriteButton(item) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'secondary save-announcement';
  const saved = savedAnnouncementIds.has(item.id);
  button.textContent = saved ? '관심 저장됨' : '관심 저장';
  button.setAttribute('aria-pressed', String(saved));
  button.addEventListener('click', () => toggleSavedAnnouncement(item.id));
  return button;
}

function candidateCard(item, detail, candidate) {
  const card = document.createElement('article');
  card.className = `candidate-card candidate-${candidate.kind.toLowerCase()}`;
  const main = document.createElement('div');
  main.className = 'candidate-main';
  const eyebrow = document.createElement('p');
  eyebrow.className = 'candidate-label';
  eyebrow.textContent = candidate.kind === 'COMPARABLE' ? '비교 가능' : '정보 확인 필요';
  const title = document.createElement('h3');
  title.textContent = listValue(item.title);
  const titleLine = document.createElement('div');
  titleLine.className = 'title-line';
  titleLine.append(title, eyebrow);
  const source = document.createElement('p');
  source.className = 'announcement-source';
  source.textContent = `${listValue(item.sourceName)} · ${listValue(item.housingType)}`;
  const facts = document.createElement('dl');
  facts.className = 'candidate-facts';
  const options = verifiedOptions(detail);
  const matching = candidate.matchingOptions || [];
  const single = matching.length === 1 ? matching[0] : null;
  fact(facts, '지역', candidate.location || '위치 확인 필요');
  fact(facts, '보증금', single ? (formatWon(single.depositWon) || '공고문 확인 필요') : (matching.length > 1 ? `조건에 맞는 ${matching.length}개 조합` : (options.length ? '주택형별 상이 · 공고문 확인' : '공고문 확인 필요')));
  fact(facts, '월세', single ? (formatWon(single.monthlyRentWon) || '공고문 확인 필요') : (matching.length > 1 ? `조건에 맞는 ${matching.length}개 조합` : (options.length ? '주택형별 상이 · 공고문 확인' : '공고문 확인 필요')));
  fact(facts, '전용면적', detail ? exclusiveArea(detail) : '공고문 확인 필요');
  fact(facts, '접수 마감', deadlineForList(item, detail));
  const reasons = document.createElement('div');
  reasons.className = 'candidate-reasons';
  for (const reason of [...candidate.reasons, ...candidate.unknown]) {
    const tag = document.createElement('span');
    tag.textContent = reason;
    reasons.append(tag);
  }
  if (candidate.eligibilityNotice) {
    const notice = document.createElement('p');
    notice.className = 'eligibility-notice';
    notice.textContent = '자격 확인 필요: 소득·자산·무주택 등 신청 요건은 공식 공고문에서 별도로 확인하세요.';
    main.append(titleLine, source, facts, reasons, notice);
  } else main.append(titleLine, source, facts, reasons);
  const actions = document.createElement('div');
  actions.className = 'candidate-actions announcement-actions';
  actions.append(drawerButton(item, detail), favoriteButton(item));
  if (hasText(item.officialUrl)) {
    const link = document.createElement('a');
    link.className = 'official';
    setOfficialLink(link, item.officialUrl, '공식 공고문 보기', listValue(item.title));
    actions.append(link);
  }
  card.append(main, actions);
  return card;
}

function renderInterestCandidates() {
  const target = $('#interest-candidates');
  const entries = (state?.allAnnouncements?.items || []).map((item) => ({ item, detail: verifiedDetailFor(item) }))
    .map((entry) => ({ ...entry, candidate: classifyInterest(entry.item, entry.detail, interestPreferences) }))
    .filter((entry) => entry.candidate.kind !== 'NONE')
    .filter((entry) => interestPreferences.includeUnknown || entry.candidate.kind !== 'NEEDS_INFO');
  const byDeadline = (left, right) => String(left.item.applicationEnd || '9999-12-31').localeCompare(String(right.item.applicationEnd || '9999-12-31'));
  const comparable = entries.filter((entry) => entry.candidate.kind === 'COMPARABLE').sort(byDeadline);
  const incomplete = entries.filter((entry) => entry.candidate.kind === 'NEEDS_INFO').sort(byDeadline);
  $('#interest-candidate-count').textContent = `${entries.length.toLocaleString('ko-KR')}건`;
  target.replaceChildren();
  if (!entries.length) {
    const message = document.createElement('p');
    message.className = 'muted empty-state';
    message.textContent = interestPreferences.regions.length ? '명시 위치와 확인된 조건으로 바로 비교할 후보가 없습니다. 정보 미기재 공고 포함을 켜거나 공식 공고문을 확인하세요.' : '관심 지역과 예산·면적을 입력하면 비교 가능한 공고와 정보 확인이 필요한 공고를 나눠 보여 줍니다.';
    target.append(message);
    return;
  }
  let remaining = HOME_PREVIEW_SIZE;
  for (const [heading, items] of [['비교 가능', comparable], ['정보 확인 필요', incomplete]]) {
    if (!items.length) continue;
    const group = document.createElement('section');
    group.className = 'candidate-group';
    const title = document.createElement('h3');
    const previewed = items.slice(0, remaining);
    remaining -= previewed.length;
    if (!previewed.length) continue;
    title.textContent = `${heading} ${items.length.toLocaleString('ko-KR')}건${previewed.length < items.length ? ` · ${previewed.length}건 표시` : ''}`;
    const cards = document.createElement('div');
    cards.className = 'candidate-cards';
    previewed.forEach((entry) => cards.append(candidateCard(entry.item, entry.detail, entry.candidate)));
    group.append(title, cards);
    if (previewed.length < items.length) {
      const note = document.createElement('p');
      note.className = 'muted candidate-preview-note';
      note.textContent = `나머지 ${(items.length - previewed.length).toLocaleString('ko-KR')}건은 전체 공고의 ‘정보 확인 필요’ 필터에서 확인할 수 있습니다.`;
      group.append(note);
    }
    target.append(group);
  }
}

function renderNewAnnouncements() {
  const target = $('#new-announcements');
  const discovered = (state?.discoveries || []).filter((item) => item?.id);
  const baseline = newItemsBaselineAt();
  const failures = failureNames(state?.monitor || {});
  $('#new-announcement-count').textContent = `${discovered.length.toLocaleString('ko-KR')}건`;
  $('#new-announcement-note').textContent = `${baseline ? `직전 성공 수집(${formatDate(baseline)})과 비교해 새로 발견된 공고입니다.` : '직전 성공 수집과 비교해 새로 발견된 공고입니다.'}${failures.length ? ' 일부 출처는 실패해 기존 마지막 정상 데이터가 유지됩니다.' : ''}`;
  target.replaceChildren();
  if (!discovered.length) {
    const empty = document.createElement('p');
    empty.className = 'muted';
    empty.textContent = '이번 수집에서 새로 발견된 공고가 없습니다.';
    target.append(empty);
    return;
  }
  const catalog = new Map((state.allAnnouncements?.items || []).map((item) => [item.id, item]));
  for (const discovery of discovered.slice(0, HOME_PREVIEW_SIZE)) {
    const item = catalog.get(discovery.id) || discovery;
    const detail = verifiedDetailFor(item);
    const card = document.createElement('article');
    card.className = 'compact-announcement';
    const title = document.createElement('h3');
    title.textContent = listValue(item.title || discovery.name);
    const meta = document.createElement('p');
    meta.className = 'announcement-source';
    meta.textContent = `${listValue(item.sourceName)} · ${deadlineForList(item, detail)}`;
    const note = document.createElement('p');
    note.className = 'muted';
    note.textContent = announcementInfoText(item, detail);
    const actions = document.createElement('div');
    actions.className = 'announcement-actions';
    actions.append(drawerButton(item, detail), favoriteButton(item));
    if (hasText(item.officialUrl)) {
      const link = document.createElement('a');
      link.className = 'official';
      setOfficialLink(link, item.officialUrl, '공식 공고문 보기', listValue(item.title));
      actions.append(link);
    }
    card.append(title, meta, note, actions);
    target.append(card);
  }
}

function summaryCard(title, count, description, hash) {
  const card = document.createElement('a');
  card.className = 'summary-card';
  card.href = hash;
  const heading = document.createElement('span');
  heading.className = 'summary-card-title';
  heading.textContent = title;
  const value = document.createElement('strong');
  value.textContent = `${Number(count || 0).toLocaleString('ko-KR')}건`;
  const note = document.createElement('span');
  note.className = 'summary-card-note';
  note.textContent = description;
  card.append(heading, value, note);
  return card;
}

function renderHomeSummary() {
  const catalogItems = state?.allAnnouncements?.items || [];
  const candidates = catalogItems
    .map((item) => classifyInterest(item, verifiedDetailFor(item), interestPreferences))
    .filter((candidate) => candidate.kind !== 'NONE')
    .filter((candidate) => interestPreferences.includeUnknown || candidate.kind !== 'NEEDS_INFO');
  const verified = (state?.verifiedAnnouncements || []).length;
  const summary = $('#summary');
  summary.replaceChildren(
    summaryCard('이번 수집 신규 공고', (state?.discoveries || []).filter((item) => item?.id).length, '직전 성공 수집과 비교한 새 공고', '#announcements'),
    summaryCard('관심 조건 일치 공고', candidates.length, '비교 가능 또는 정보 확인 필요 공고', '#announcements'),
    summaryCard('상세 확인 완료 공고', verified, '비용·위치·면적을 확인한 공고', '#announcements'),
  );
}

function announcementTitleCell(item, detail, newIds) {
  const cell = document.createElement('td');
  cell.dataset.label = '공고';
  const title = document.createElement('h3');
  title.className = 'announcement-title';
  if (hasText(item.officialUrl)) {
    const link = document.createElement('a');
    setOfficialLink(link, item.officialUrl, listValue(item.title), '공식 공고문');
    title.append(link);
  } else {
    title.textContent = listValue(item.title);
  }
  const source = document.createElement('p');
  source.className = 'announcement-source';
  source.textContent = `${listValue(item.sourceName)} · ${listValue(item.housingType)} · ${listValue(item.provider)}`;
  const badges = document.createElement('div');
  badges.className = 'announcement-badges';
  if (newIds.has(item.id)) {
    const badge = document.createElement('span');
    badge.className = 'status';
    badge.textContent = '신규';
    badges.append(badge);
  }
  if (detail) {
    const badge = document.createElement('span');
    badge.className = 'verified-badge';
    badge.textContent = '상세 확인 완료';
    badges.append(badge);
  }
  cell.append(title, source);
  if (badges.childElementCount) cell.append(badges);
  cell.append(drawerButton(item, detail));
  cell.append(favoriteButton(item));
  return cell;
}

function textCell(label, value) {
  const cell = document.createElement('td');
  cell.dataset.label = label;
  cell.textContent = value;
  return cell;
}

function appendAnnouncementRows(body, items, newIds) {
  for (const item of items) {
    const detail = verifiedDetailFor(item);
    const row = document.createElement('tr');
    row.className = detail ? 'has-details' : 'needs-details';
    row.append(
      announcementTitleCell(item, detail, newIds),
      textCell('지역', listLocation(item, detail)),
      textCell('보증금', costForList(detail, 'depositWon')),
      textCell('월세', costForList(detail, 'monthlyRentWon')),
      textCell('전용면적', detail ? exclusiveArea(detail) : NEEDS_NOTICE),
      textCell('접수 마감', deadlineForList(item, detail)),
      textCell('상태', listValue(item.status)),
    );
    body.append(row);
  }
}

function announcementTable() {
  const table = document.createElement('table');
  table.className = 'announcement-table';
  table.setAttribute('aria-describedby', 'announcement-result-count');
  const head = document.createElement('thead');
  const headerRow = document.createElement('tr');
  for (const label of ['공고', '지역', '보증금', '월세', '전용면적', '접수 마감', '상태']) {
    const header = document.createElement('th');
    header.scope = 'col';
    header.textContent = label;
    headerRow.append(header);
  }
  head.append(headerRow);
  table.append(head, document.createElement('tbody'));
  return table;
}

function invalidateAnnouncementRender() {
  $('#announcement-loading').hidden = true;
}

function queueAnnouncementAppend() {
  const total = filteredAnnouncements().length;
  if (announcementLimit >= total) return;
  const more = $('#load-more-announcements');
  more.disabled = true;
  announcementLimit = Math.min(announcementLimit + ANNOUNCEMENT_PAGE_SIZE, total);
  renderAllAnnouncements(true);
  more.disabled = false;
}

function renderAllAnnouncements(append = false) {
  $('#load-more-announcements').hidden = true;
  const catalog = state.allAnnouncements || { items: [] };
  const items = catalog.items || [];
  const visible = filteredAnnouncements();
  const shown = visible.slice(0, announcementLimit);
  const heading = catalog.mode === 'full' ? '전체 목록' : (catalog.mode || '아직 수집 전');
  $('#announcement-count').textContent = `${heading} · 마지막 성공 수집 ${formatDate(catalog.checkedAt)}`;
  $('#announcement-result-count').textContent = visible.length
    ? `${appliedFilterSummary()}검색 결과 ${visible.length.toLocaleString('ko-KR')}건${visible.length > shown.length ? ` · ${shown.length.toLocaleString('ko-KR')}건 표시` : ''}`
    : '조건에 맞는 공고가 없습니다. 검색 조건을 조정해 보세요.';

  const target = $('#all-announcements');
  const loading = $('#announcement-loading');
  loading.hidden = true;
  target.classList.remove('announcement-scrollable');

  if (!items.length) {
    target.replaceChildren();
    const message = document.createElement('p');
    message.className = 'muted';
    message.textContent = '아직 구조화된 목록을 수집하지 않았습니다. 지금 공고 수집을 눌러 공개 목록 전체를 불러올 수 있습니다.';
    target.append(message);
    return;
  }
  if (!visible.length) {
    target.replaceChildren();
    const message = document.createElement('p');
    message.className = 'muted';
    message.textContent = '조건에 맞는 공고가 없습니다. 검색 조건을 조정해 보세요.';
    target.append(message);
    return;
  }
  const newIds = newAnnouncementIds();
  const previousScrollTop = target.scrollTop;
  let table = append ? $('.announcement-table', target) : null;
  if (!table) {
    table = announcementTable();
    target.replaceChildren(table);
    appendAnnouncementRows($('tbody', table), shown, newIds);
  } else {
    const start = Math.max(0, shown.length - ANNOUNCEMENT_PAGE_SIZE);
    appendAnnouncementRows($('tbody', table), shown.slice(start), newIds);
    target.scrollTop = previousScrollTop;
  }
  const more = $('#load-more-announcements');
  more.hidden = visible.length <= shown.length;
  if (!more.hidden) more.textContent = `공고 ${Math.min(ANNOUNCEMENT_PAGE_SIZE, visible.length - shown.length).toLocaleString('ko-KR')}건 더 보기`;
}

function setupAnnouncementCatalog(view = null) {
  const items = state.allAnnouncements?.items || [];
  invalidateAnnouncementRender();
  announcementLimit = view ? Math.max(ANNOUNCEMENT_PAGE_SIZE, view.limit) : ANNOUNCEMENT_PAGE_SIZE;
  setSelectOptions('#announcement-source', optionValues(items, 'sourceName'));
  setSelectOptions('#announcement-status', optionValues(items, 'status'));
  setSelectOptions('#announcement-region', regionOptionValues(items));
  setSelectOptions('#announcement-housing-type', optionValues(items, 'housingType'));
  catalogState.draftFilters.source = $('#announcement-source').value;
  catalogState.draftFilters.status = $('#announcement-status').value;
  catalogState.draftFilters.region = $('#announcement-region').value;
  catalogState.draftFilters.housingType = $('#announcement-housing-type').value;
  const newOnly = $('#announcement-new-only');
  const hasNewItems = newAnnouncementIds().size > 0;
  newOnly.disabled = !hasNewItems;
  if (!hasNewItems) {
    catalogState.draftFilters.newOnly = false;
    catalogState.appliedFilters.newOnly = false;
  }
  if (!optionValues(items, 'sourceName').includes(catalogState.appliedFilters.source)) catalogState.appliedFilters.source = '';
  if (!optionValues(items, 'status').includes(catalogState.appliedFilters.status)) catalogState.appliedFilters.status = '';
  if (!regionOptionValues(items).includes(catalogState.appliedFilters.region)) catalogState.appliedFilters.region = '';
  if (!optionValues(items, 'housingType').includes(catalogState.appliedFilters.housingType)) catalogState.appliedFilters.housingType = '';
  writeDraftFilters();
  updateAnnouncementFilterNote();
  renderAllAnnouncements();
  renderInterestCandidates();
  renderNewAnnouncements();
  if (view) window.scrollTo({ top: view.scrollTop, behavior: 'instant' });
}

function updateAnnouncementFilterNote() {
  const baseline = newItemsBaselineAt();
  const baselineText = baseline ? `신규는 직전 성공 수집(${formatDate(baseline)})과 비교해 새로 발견된 공고만을 뜻합니다.` : '신규 기준 수집 시각이 없어 신규 필터는 현재 사용할 수 없습니다.';
  $('#announcement-filter-note').textContent = `필터는 화면 표시 기능이며, 수집 대상이나 개인 프로필을 바꾸지 않습니다. ${baselineText}`;
}

function failureNames(monitor) {
  if (Array.isArray(monitor?.failedSources) && monitor.failedSources.length) return monitor.failedSources;
  return Object.entries(monitor?.sources || {})
    .filter(([, source]) => source?.error)
    .map(([source, value]) => ({ source, error: value.error }));
}

function renderCollectionState() {
  const monitor = state.monitor || {};
  const catalog = state.allAnnouncements || {};
  const failures = failureNames(monitor);
  const newCount = Array.isArray(state.notifications?.actionable) ? state.notifications.actionable.length : null;
  const lastSuccess = catalog.checkedAt || monitor.lastSuccessfulAt;
  $('#header-collection-time').textContent = formatHeaderDate(lastSuccess);
  const outcome = monitor.outcome || (failures.length ? 'SUCCEEDED_WITH_WARNINGS' : (lastSuccess ? 'SUCCEEDED' : 'IDLE'));
  const status = $('#collection-status');
  status.replaceChildren();
  const resultText = outcome === 'FAILED' ? '수집 실패' : outcome === 'SUCCEEDED_WITH_WARNINGS' ? '일부 출처 실패' : outcome === 'SUCCEEDED' ? '수집 성공' : '수집 기록 없음';
  for (const [label, value, className] of [
    ['마지막 성공 수집', lastSuccess ? formatDate(lastSuccess) : '아직 없음', ''],
    ['수집 결과', resultText, `collection-outcome ${outcome.toLowerCase()}`],
    ['전체 수집 공고 수', `${Number(catalog.itemCount ?? catalog.items?.length ?? 0).toLocaleString('ko-KR')}건`, ''],
  ]) {
    const item = document.createElement('div');
    item.className = 'collection-summary-item';
    const heading = document.createElement('span');
    heading.textContent = label;
    const content = document.createElement('strong');
    content.className = className;
    content.textContent = value;
    item.append(heading, content);
    status.append(item);
  }
  if (newCount != null) $('#collection-result').textContent = `이번 수집 신규 공고 ${newCount.toLocaleString('ko-KR')}건`;
  if (failures.length) {
    const failed = document.createElement('p');
    failed.className = 'collection-failures';
    failed.textContent = `실패 출처: ${failures.map((failure) => `${failure.source}${failure.error ? ` (${failure.error})` : ''}`).join(', ')}`;
    status.append(failed);
  }
}

function programCard(program, index) {
  const card = document.createElement('article');
  card.className = 'program';
  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'program-toggle';
  toggle.setAttribute('aria-expanded', 'false');
  const details = document.createElement('div');
  details.className = 'program-details';
  details.id = `program-details-${index}`;
  details.hidden = true;
  toggle.setAttribute('aria-controls', details.id);
  const title = document.createElement('h3');
  title.textContent = program.name || '제도 안내';
  const summary = document.createElement('span');
  summary.textContent = [program.type, program.provider].filter(hasText).join(' · ');
  toggle.append(title, summary);
  for (const [label, value] of [['모집 상태', program.status], ['비용', program.cost], ['자격 안내', program.eligibility]]) {
    if (!hasText(value)) continue;
    const paragraph = document.createElement('p');
    paragraph.textContent = `${label}: ${value}`;
    details.append(paragraph);
  }
  if (hasText(program.officialUrl)) {
    const link = document.createElement('a');
    setOfficialLink(link, program.officialUrl, '공식 안내', program.name || '제도');
    details.append(link);
  }
  toggle.addEventListener('click', () => {
    const expanded = toggle.getAttribute('aria-expanded') !== 'true';
    toggle.setAttribute('aria-expanded', String(expanded));
    details.hidden = !expanded;
  });
  card.append(toggle, details);
  return card;
}

function renderPrograms(programs, expanded = false) {
  const target = $('#programs');
  target.replaceChildren(...programs.slice(0, expanded ? programs.length : PROGRAM_PREVIEW_SIZE).map(programCard));
  const more = $('#load-more-programs');
  more.hidden = programs.length <= PROGRAM_PREVIEW_SIZE;
  if (more.hidden) return;
  more.textContent = expanded ? '제도 접기' : `제도 ${programs.length - PROGRAM_PREVIEW_SIZE}개 더 보기`;
  more.onclick = () => renderPrograms(programs, !expanded);
}

function renderMonitor(monitor, discoveries) {
  const rows = Object.entries(monitor?.sources || {});
  const monitorTarget = $('#monitor');
  monitorTarget.replaceChildren();
  if (!monitor) {
    const empty = document.createElement('p'); empty.className = 'muted'; empty.textContent = '아직 실행하지 않았습니다. 지금 공고 수집을 눌러 공식 목록을 확인하세요.'; monitorTarget.append(empty);
  } else {
    const sourceNames = new Map((state.allAnnouncements?.items || []).filter((item) => hasText(item.sourceId) && hasText(item.sourceName)).map((item) => [item.sourceId, item.sourceName]));
    for (const [id, item] of rows) {
      const row = document.createElement('div');
      row.className = `monitor-row ${item.error ? 'monitor-error' : 'monitor-ok'}`;
      const name = document.createElement('strong'); name.textContent = sourceNames.get(id) || item.sourceName || id;
      const result = document.createElement('span'); result.textContent = item.error ? `실패 · ${item.error} · 기존 마지막 정상 데이터 유지` : `정상 · ${Number(item.recordCount || 0).toLocaleString('ko-KR')}건`;
      row.append(name, result); monitorTarget.append(row);
    }
  }
  $('#discoveries').innerHTML = discoveries.length ? `<div class="discovery"><strong>새로 발견된 공고 ${discoveries.length}건</strong>${discoveries.slice(0, 8).map((item) => `<p><a target="_blank" rel="noopener noreferrer" aria-label="${escapeAttribute(item.name)} 공식 공고문 (새 탭에서 열림)" href="${escapeAttribute(item.officialUrl)}">${escapeHtml(item.name)}</a><br><span class="muted">${escapeHtml(item.reason || '직전 성공 수집과 비교해 새로 발견')}</span></p>`).join('')}</div>` : '';
}

function escapeHtml(value) { return String(value || '').replace(/[&<>\"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char])); }
function escapeAttribute(value) { return escapeHtml(value).replace(/'/g, '&#39;'); }

function populateForm(profile) {
  const form = $('#profile-form');
  for (const element of form.elements) {
    if (!element.name) continue;
    const value = profile[element.name];
    const displayValue = Array.isArray(value) ? value.join(', ') : (value !== null && value !== undefined && value !== '' && value !== '미확인' ? value : '');
    element.value = element.matches('[data-numeric-format]') ? formatNumericValue(displayValue) : displayValue;
  }
}

function overviewPanels() {
  return [...document.querySelectorAll('.overview-panel')];
}

function setOverviewRefreshing(refreshing) {
  for (const panel of overviewPanels()) {
    panel.classList.toggle('is-refreshing', refreshing);
    if (refreshing) panel.setAttribute('aria-busy', 'true');
    else panel.removeAttribute('aria-busy');
  }
}

function announcementView() {
  return state ? { limit: announcementLimit, scrollTop: $('#all-announcements').scrollTop } : null;
}

function skeletonBlock(className) {
  const block = document.createElement('div');
  block.className = `skeleton ${className}`;
  block.setAttribute('aria-hidden', 'true');
  return block;
}

function showInitialSkeleton() {
  const notices = $('#notices');
  notices.replaceChildren(...Array.from({ length: 3 }, () => skeletonBlock('skeleton-card')));
  const list = $('#all-announcements');
  list.replaceChildren(...Array.from({ length: 5 }, () => skeletonBlock('skeleton-row')));
}

function initialLoadError(error) {
  const notices = $('#notices');
  const message = document.createElement('div');
  message.className = 'initial-load-error';
  const heading = document.createElement('p');
  heading.textContent = '데이터를 불러오지 못했습니다.';
  const detail = document.createElement('p');
  detail.className = 'muted';
  detail.textContent = error.message || '네트워크 상태를 확인한 뒤 다시 시도하세요.';
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'secondary';
  retry.textContent = '다시 시도';
  retry.addEventListener('click', reloadPage);
  message.append(heading, detail, retry);
  notices.replaceChildren(message);
  $('#all-announcements').replaceChildren();
}

function showToast({ type, message, detail = '', retry = false }) {
  window.clearTimeout(toastTimer);
  const region = $('#toast-region');
  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  const icon = document.createElement('span');
  icon.className = 'toast-icon';
  icon.setAttribute('aria-hidden', 'true');
  icon.textContent = type === 'success' ? '✓' : '!';
  const content = document.createElement('div');
  content.className = 'toast-content';
  const title = document.createElement('p');
  title.className = 'toast-title';
  title.textContent = message;
  content.append(title);
  if (detail) {
    const supporting = document.createElement('p');
    supporting.className = 'toast-detail';
    supporting.textContent = detail;
    content.append(supporting);
  }
  const actions = document.createElement('div');
  actions.className = 'toast-actions';
  if (retry) {
    const retryButton = document.createElement('button');
    retryButton.type = 'button';
    retryButton.className = 'secondary';
    retryButton.textContent = '다시 시도';
    retryButton.addEventListener('click', reloadPage);
    actions.append(retryButton);
  }
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'toast-close';
  close.setAttribute('aria-label', '알림 닫기');
  close.textContent = '닫기';
  close.addEventListener('click', () => { window.clearTimeout(toastTimer); region.replaceChildren(); });
  actions.append(close);
  toast.append(icon, content, actions);
  region.replaceChildren(toast);
  toastTimer = window.setTimeout(() => region.replaceChildren(), type === 'success' ? 3500 : 7500);
}

async function load({ refreshing = false } = {}) {
  const priorView = announcementView();
  if (!priorView) showInitialSkeleton();
  if (refreshing && priorView) setOverviewRefreshing(true);
  const response = await fetch('/api/overview');
  if (!response.ok) throw new Error('저장된 화면 데이터를 불러오지 못했습니다.');
  const nextState = await response.json();
  state = nextState;
  renderHomeSummary();
  renderVerifiedAnnouncements();
  setupAnnouncementCatalog(priorView);
  renderCollectionState();
  renderPrograms(state.programs || []);
  renderMonitor(state.monitor, state.discoveries || []);
  populateForm(state.profile || {});
}

const reloadButton = $('#reload');
const reloadResult = $('#reload-result');
async function reloadPage() {
  if (reloadInFlight) return;
  reloadInFlight = true;
  reloadButton.disabled = true;
  reloadButton.setAttribute('aria-busy', 'true');
  reloadButton.classList.add('is-loading');
  reloadResult.textContent = '';
  try {
    await load({ refreshing: true });
    reloadResult.textContent = '최신 데이터로 업데이트되었습니다.';
    showToast({ type: 'success', message: '최신 데이터로 업데이트되었습니다.' });
  } catch (error) {
    if (!state) initialLoadError(error);
    reloadResult.textContent = '데이터를 다시 불러오지 못했습니다.';
    showToast({ type: 'error', message: '데이터를 다시 불러오지 못했습니다.', detail: error.message, retry: true });
  } finally {
    setOverviewRefreshing(false);
    reloadInFlight = false;
    reloadButton.disabled = false;
    reloadButton.removeAttribute('aria-busy');
    reloadButton.classList.remove('is-loading');
  }
}
reloadButton.addEventListener('click', reloadPage);

const collectionButton = $('#collect-announcements');
const collectionResult = $('#collection-result');
function setCollectionRequest(message, busy = false) {
  collectionResult.textContent = message;
  collectionButton.disabled = busy;
  collectionButton.classList.toggle('is-loading', busy);
  if (busy) collectionButton.setAttribute('aria-busy', 'true');
  else collectionButton.removeAttribute('aria-busy');
  $('span', collectionButton).textContent = busy ? '공식 목록 수집 중…' : '지금 공고 수집';
}

function collectionSummary(job) {
  const newCount = Number(job.newItemCount || 0).toLocaleString('ko-KR');
  const total = job.catalogCount == null ? null : Number(job.catalogCount).toLocaleString('ko-KR');
  const checked = (job.checked || []).map((item) => `${item.source} ${Number(item.recordCount || 0).toLocaleString('ko-KR')}건`).join(', ');
  const checkedSummary = checked ? ` · ${checked}` : '';
  if (job.status === 'SUCCEEDED') return `수집 성공: ${total ? `전체 ${total}건 · ` : ''}신규 ${newCount}건${checkedSummary}`;
  if (job.status === 'SUCCEEDED_WITH_WARNINGS') {
    const names = (job.failures || []).map((item) => item.source).filter(Boolean);
    const warning = names.length ? `실패 출처: ${names.join(', ')} · 기존 마지막 정상 데이터 유지` : '일부 출처 실패 · 기존 마지막 정상 데이터 유지';
    return `수집 일부 성공: 신규 ${newCount}건 · ${warning}${checkedSummary}`;
  }
  return '수집 실패: 기존 마지막 정상 목록은 유지됩니다.';
}

async function trackCollection(startedAt = Date.now()) {
  if (Date.now() - startedAt >= 10 * 60 * 1000) {
    setCollectionRequest('수집 요청은 계속 처리 중일 수 있습니다. 화면 다시 불러오기로 결과를 확인하세요.');
    return;
  }
  try {
    const response = await fetch('/api/monitor/refresh');
    if (!response.ok) throw new Error('수집 상태를 확인하지 못했습니다.');
    const job = await response.json();
    if (job.status === 'RUNNING' || job.status === 'IDLE') {
      setCollectionRequest('공식 목록을 수집 중입니다. 완료까지 잠시 걸릴 수 있습니다.', true);
      window.setTimeout(() => trackCollection(startedAt), 2000);
      return;
    }
    if (job.status === 'SUCCEEDED' || job.status === 'SUCCEEDED_WITH_WARNINGS') await load();
    const summary = collectionSummary(job);
    setCollectionRequest(summary);
    if (job.status === 'SUCCEEDED') showToast({ type: 'success', message: '공고 수집이 완료되었습니다.', detail: summary });
    else if (job.status === 'SUCCEEDED_WITH_WARNINGS') showToast({ type: 'warning', message: '일부 기관의 수집 결과를 확인해 주세요.', detail: summary });
    else showToast({ type: 'error', message: '공고 수집에 실패했습니다.', detail: summary });
  } catch (error) {
    setCollectionRequest(`수집 상태를 확인하지 못했습니다: ${error.message}`);
    showToast({ type: 'error', message: '수집 상태를 확인하지 못했습니다.', detail: error.message });
  }
}

collectionButton.addEventListener('click', async () => {
  setCollectionRequest('수집 요청을 보냈습니다. 공식 목록을 수집 중입니다.', true);
  try {
    const response = await fetch('/api/monitor/refresh', { method: 'POST' });
    if (response.status === 429) {
      setCollectionRequest('최근 수집 직후에는 10분 뒤 다시 요청할 수 있습니다.');
      showToast({ type: 'warning', message: '잠시 후 다시 시도해 주세요.', detail: '최근 수집 직후에는 10분 뒤 다시 요청할 수 있습니다.' });
      return;
    }
    if (response.status !== 202 && response.status !== 409) throw new Error('수집을 시작하지 못했습니다.');
    await trackCollection();
  } catch (error) {
    setCollectionRequest(`수집을 시작하지 못했습니다: ${error.message}`);
    showToast({ type: 'error', message: '수집을 시작하지 못했습니다.', detail: error.message });
  }
});

function resetAnnouncementLimit() {
  invalidateAnnouncementRender();
  announcementLimit = ANNOUNCEMENT_PAGE_SIZE;
  updateAnnouncementFilterNote();
  renderAllAnnouncements();
}

function applyAnnouncementFilters() {
  syncDraftFilters();
  const changed = JSON.stringify(catalogState.appliedFilters) !== JSON.stringify(catalogState.draftFilters);
  if (!changed) return;
  const button = $('#apply-announcement-filters');
  button.classList.add('is-applying');
  button.setAttribute('aria-busy', 'true');
  catalogState.appliedFilters = { ...catalogState.draftFilters };
  resetAnnouncementLimit();
  window.requestAnimationFrame(() => {
    button.classList.remove('is-applying');
    button.removeAttribute('aria-busy');
  });
}

function resetAnnouncementFilters() {
  catalogState.draftFilters = createDefaultCatalogFilters();
  catalogState.appliedFilters = createDefaultCatalogFilters();
  writeDraftFilters();
  resetAnnouncementLimit();
}
for (const selector of ['#announcement-source', '#announcement-status', '#announcement-information', '#announcement-new-only', '#announcement-include-unknown', '#announcement-region', '#announcement-housing-type', '#announcement-deposit-max', '#announcement-rent-max', '#announcement-area-min', '#announcement-area-max']) {
  $(selector).addEventListener('change', syncDraftFilters);
}
$('#announcement-query').addEventListener('input', syncDraftFilters);
$('#announcement-query').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    event.preventDefault();
    applyAnnouncementFilters();
  }
});
$('#global-search').addEventListener('submit', (event) => {
  event.preventDefault();
  const query = $('#global-query').value.trim();
  $('#announcement-query').value = query;
  catalogState.draftFilters.query = query;
  window.location.hash = '#announcements';
  if (state) applyAnnouncementFilters();
  else catalogState.appliedFilters.query = query;
});
$('#apply-announcement-filters').addEventListener('click', applyAnnouncementFilters);
$('#reset-announcement-filters').addEventListener('click', resetAnnouncementFilters);
$('#load-more-announcements').addEventListener('click', queueAnnouncementAppend);
$('#interest-form').addEventListener('submit', (event) => {
  event.preventDefault();
  saveInterestPreferences();
});
for (const input of document.querySelectorAll('[data-numeric-format]')) {
  input.addEventListener('input', () => {
    formatNumericInput(input);
    if (input.id === 'interest-area-min' || input.id === 'interest-area-max') setAreaValidation();
    if (input.id.startsWith('announcement-')) syncDraftFilters();
  });
}
$('#reset-interest-preferences').addEventListener('click', () => {
  interestPreferences = { regions: [], depositMaxWon: null, monthlyRentMaxWon: null, areaMin: null, areaMax: null, includeUnknown: true };
  $('#interest-regions').value = '';
  $('#interest-deposit-max').value = '';
  $('#interest-rent-max').value = '';
  $('#interest-area-min').value = '';
  $('#interest-area-max').value = '';
  $('#interest-include-unknown').checked = true;
  setAreaValidation();
  try { localStorage.removeItem(INTEREST_STORAGE_KEY); } catch { /* Local storage is optional. */ }
  $('#interest-save-status').textContent = '관심 조건을 초기화했습니다.';
  renderInterestPreferenceSummary();
  renderInterestCandidates();
  renderHomeSummary();
  showToast({ type: 'success', message: '관심 조건을 초기화했습니다.' });
});

$('#toggle-announcement-details').addEventListener('click', () => {
  const button = $('#toggle-announcement-details');
  const details = $('#announcement-detail-filters');
  const expanded = button.getAttribute('aria-expanded') !== 'true';
  button.setAttribute('aria-expanded', String(expanded));
  button.textContent = expanded ? '상세 조건 접기' : '상세 조건';
  details.hidden = !expanded;
});

$('#profile-details-toggle').addEventListener('click', () => {
  const button = $('#profile-details-toggle');
  const form = $('#profile-form');
  const expanded = button.getAttribute('aria-expanded') !== 'true';
  button.setAttribute('aria-expanded', String(expanded));
  button.textContent = expanded ? '추가 프로필 메모 접기' : '추가 프로필 메모';
  form.hidden = !expanded;
});

$('#profile-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  data.preferredAreas = data.preferredAreas ? data.preferredAreas.split(',').map((item) => item.trim()).filter(Boolean) : [];
  for (const key of ['maxMonthlyRent', 'maxDeposit']) data[key] = numberOrNull(data[key]);
  const response = await fetch('/api/profile', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data) });
  $('#save-result').textContent = response.ok ? '저장했습니다. 이 메모는 공고 수집·표시에는 적용하지 않습니다.' : '저장하지 못했습니다.';
  if (response.ok) await load();
});

$('#announcement-drawer-close').addEventListener('click', closeAnnouncementDrawer);
$('#announcement-drawer-backdrop').addEventListener('click', closeAnnouncementDrawer);
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeAnnouncementDrawer();
  else trapDrawerFocus(event);
});

const VIEW_IDS = ['home', 'announcements', 'preferences', 'collection', 'programs'];

function getViewFromHash() {
  const view = window.location.hash.replace('#', '');
  return VIEW_IDS.includes(view) ? view : 'home';
}

function setActiveView(view) {
  const activeView = VIEW_IDS.includes(view) ? view : 'home';
  document.querySelectorAll('[data-view]').forEach((section) => {
    section.hidden = section.dataset.view !== activeView;
  });
  document.querySelectorAll('[data-view-link]').forEach((link) => {
    if (link.dataset.viewLink === activeView) {
      link.setAttribute('aria-current', 'page');
    } else {
      link.removeAttribute('aria-current');
    }
  });
  document.title = `${({ home: '홈', announcements: '공고 찾기', preferences: '관심 조건', collection: '공고 수집', programs: '제도 안내' })[activeView]} | 청약 알림`;
  $('#app-navigation').classList.remove('is-open');
  if (activeView === 'preferences') setAreaValidation();
  window.scrollTo({ top: 0, behavior: 'instant' });
}

window.addEventListener('hashchange', () => setActiveView(getViewFromHash()));

loadInterestPreferences();
setActiveView(getViewFromHash());
load().catch((error) => {
  $('#summary').textContent = `대시보드를 불러오지 못했습니다: ${error.message}`;
  initialLoadError(error);
  showToast({ type: 'error', message: '데이터를 다시 불러오지 못했습니다.', detail: error.message, retry: true });
});
