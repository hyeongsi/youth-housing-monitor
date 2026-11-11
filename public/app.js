import { areaSquareMeters, classifyInterest, explicitLocation, verifiedOptions } from './announcement-explorer.js';
import { announcementMapSearchQuery, describeAnnouncementLocations, kakaoMapSearchUrl, locationAccuracyMessage, renderAnnouncementMap } from './announcement-map.js';

const $ = (selector, root = document) => root.querySelector(selector);
const ANNOUNCEMENT_PAGE_SIZE = 10;
const PROGRAM_PREVIEW_SIZE = 6;
const MISSING_LIST_VALUE = '목록 미기재';
const NEEDS_NOTICE = '공고문 확인 필요';
const INTEREST_STORAGE_KEY = 'housing-monitor-interest-preferences-v1';
const SAVED_STORAGE_KEY = 'housing-monitor-saved-announcements-v1';
const OVERVIEW_REQUEST_TIMEOUT_MS = 15_000;

let state;
let catalogPage = { items: [], page: 1, size: ANNOUNCEMENT_PAGE_SIZE, totalCount: 0, hasNext: false };
let catalogController = null;
let toastTimer;
let drawerTrigger = null;
let drawerMapContext = null;
let drawerMapStarted = false;
let drawerMapController = null;
let interestPreferences = { regions: [], depositMaxWon: null, monthlyRentMaxWon: null, areaMin: null, areaMax: null, includeUnknown: true };
let savedAnnouncementIds = new Set();
function createDefaultCatalogFilters() {
  return { source: '', status: '', interestOnly: false, savedOnly: false, includeUnknown: true, includeClosed: false, supplementalOnly: false, query: '', region: '', housingType: '', depositMaxWon: '', monthlyRentMaxWon: '', areaMin: '', areaMax: '' };
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
  const detail = item?.detail;
  return detail?.verificationStatus === 'VERIFIED' ? detail : null;
}

function verifiedCostOptions(detail) {
  const hierarchy = (detail?.sites || []).flatMap((site) => (site.units || []).flatMap((unit) => (unit.rentalOptions || []).map((option) => ({ ...option, typeText: unit.unitType }))));
  const options = hierarchy.length ? hierarchy : detail?.costOptions;
  return Array.isArray(options) ? options.filter((option) => option && (Number.isFinite(option.depositWon) || Number.isFinite(option.monthlyRentWon))) : [];
}

function safeLocation(detail, item) {
  const values = [detail?.location?.district, detail?.location?.address].filter(hasText);
  return values.length ? values.join(' ') : listValue(item?.area);
}

function listLocation(item, detail) {
  const siteLabels = (detail?.sites || []).map((site) => site?.location?.displayLabel || site?.location?.address || site?.name).filter(hasText);
  if (siteLabels.length > 1) return `${siteLabels[0]} 외 ${siteLabels.length - 1}곳`;
  if (siteLabels.length === 1) return siteLabels[0];
  const detailed = detail ? safeLocation(detail, item) : null;
  return detailed && detailed !== MISSING_LIST_VALUE ? detailed : listValue(item.area);
}

function exclusiveArea(detail) {
  const areas = (detail?.sites || []).flatMap((site) => site.units || []).map((unit) => Number(unit.exclusiveAreaM2)).filter(Number.isFinite);
  if (areas.length) {
    const minimum = Math.min(...areas);
    const maximum = Math.max(...areas);
    return minimum === maximum ? `${minimum.toLocaleString('ko-KR')}㎡` : `${minimum.toLocaleString('ko-KR')}~${maximum.toLocaleString('ko-KR')}㎡`;
  }
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
  // 확정 PDF 일정이 있으면 목록 HTML보다 우선한다.
  if (detail?.application?.end) return formatDate(detail.application.end);
  if (!isListMissing(item.applicationEnd)) return formatDate(item.applicationEnd);
  return MISSING_LIST_VALUE;
}

function dateValue(value) {
  if (!hasText(value)) return null;
  const match = String(value).match(/(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 23, 59, 59);
  return Number.isNaN(date.getTime()) ? null : date;
}

function isClosedAnnouncement(item, detail) {
  const status = String(item.status || '');
  if (/(마감|종료)/.test(status)) return true;
  const deadline = dateValue(detail?.application?.end || item.applicationEnd);
  return deadline ? deadline.getTime() < Date.now() : false;
}

function announcementPriority(item, detail) {
  if (isClosedAnnouncement(item, detail)) return 3;
  const status = String(item.status || '');
  if (/(접수중|모집중|추가모집|공고중)/.test(status)) return 0;
  if (/(접수예정|모집예정|공고예정)/.test(status)) return 1;
  return 2;
}

function normalizedValue(value) {
  return String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ko');
}

function duplicateKey(item) {
  const source = normalizedValue(item.sourceId || item.sourceName || item.provider);
  const title = normalizedValue(item.title);
  const url = normalizedValue(item.officialUrl);
  if (url) return `url:${source}|${url}`;
  return `notice:${source}|${title}|${item.announcementDate || ''}|${item.applicationStart || ''}|${item.applicationEnd || ''}`;
}

function deduplicatedAnnouncements(items) {
  const grouped = new Map();
  for (const item of items || []) {
    const key = duplicateKey(item);
    const previous = grouped.get(key);
    if (!previous) {
      grouped.set(key, { ...item, duplicateIds: [item.id], duplicateCount: 1 });
      continue;
    }
    previous.duplicateIds.push(item.id);
    previous.duplicateCount += 1;
    const currentDetail = verifiedDetailFor(item);
    const previousDetail = verifiedDetailFor(previous);
    const currentUpdated = String(item.updatedAt || item.announcementDate || '');
    const previousUpdated = String(previous.updatedAt || previous.announcementDate || '');
    if ((!previousDetail && currentDetail) || currentUpdated > previousUpdated) {
      Object.assign(previous, item, { duplicateIds: previous.duplicateIds, duplicateCount: previous.duplicateCount });
    }
  }
  return [...grouped.values()];
}

function savedIdsFor(item) {
  return item.duplicateIds?.filter(Boolean) || [item.id].filter(Boolean);
}

function isSavedAnnouncement(item) {
  return savedIdsFor(item).some((id) => savedAnnouncementIds.has(id));
}

function costForList(detail, field) {
  if (!detail) return '공고문 확인';
  const options = verifiedCostOptions(detail);
  if (!options.length) return '공고문 확인';
  const values = options.map((option) => option[field]).filter(Number.isFinite);
  if (!values.length) return '공고문 확인';
  const minimum = Math.min(...values);
  const maximum = Math.max(...values);
  return minimum === maximum ? formatWon(minimum) : `${formatWon(minimum)} ~ ${formatWon(maximum)}`;
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
  drawerMapController?.abort();
  drawerMapContext = null;
  document.body.classList.remove('drawer-open');
  drawerTrigger?.focus();
  drawerTrigger = null;
}

function trapDrawerFocus(event) {
  const drawer = $('#announcement-drawer');
  if (drawer.hidden || event.key !== 'Tab') return;
  const focusable = [...drawer.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.closest('[hidden]'));
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

function updateDrawerTabOrientation() {
  $('#announcement-drawer-tabs').setAttribute('aria-orientation', window.matchMedia('(max-width: 720px)').matches ? 'horizontal' : 'vertical');
}

window.matchMedia('(max-width: 720px)').addEventListener('change', updateDrawerTabOrientation);

async function loadDrawerMap() {
  if (!drawerMapContext || drawerMapStarted) return;
  drawerMapStarted = true;
  const { item, detail } = drawerMapContext;
  const controller = drawerMapController;
  try {
    await renderAnnouncementMap({
      item,
      detail,
      canvas: $('#announcement-map-canvas'),
      status: $('#announcement-map-status'),
      signal: controller.signal,
    });
  } catch {
    if (!controller.signal.aborted) {
      const canvas = $('#announcement-map-canvas');
      const placeholder = document.createElement('p');
      placeholder.className = 'drawer-map-placeholder';
      placeholder.textContent = '지도를 표시하지 못했습니다.';
      canvas.hidden = false;
      canvas.replaceChildren(placeholder);
      $('#announcement-map-status').textContent = '지도를 표시하지 못했습니다. 아래 링크에서 위치를 검색해 보세요.';
      $('#announcement-map-status').dataset.state = 'error';
    }
  }
}

function activateDrawerTab(name, focusPanel = false) {
  const activeName = ['detail', 'sites', 'eligibility', 'map'].includes(name) ? name : 'detail';
  for (const tab of document.querySelectorAll('[data-drawer-tab]')) {
    const active = tab.dataset.drawerTab === activeName;
    tab.setAttribute('aria-selected', String(active));
    tab.tabIndex = active ? 0 : -1;
  }
  for (const panel of document.querySelectorAll('.drawer-panel')) {
    panel.hidden = panel.id !== `announcement-${activeName}-panel`;
  }
  const panel = $(`#announcement-${activeName}-panel`);
  if (focusPanel) panel.focus();
  if (activeName === 'map') loadDrawerMap();
}

function resetDrawerMap(item, detail) {
  drawerMapController?.abort();
  drawerMapController = new AbortController();
  drawerMapContext = { item, detail };
  drawerMapStarted = false;
  const locations = describeAnnouncementLocations(item, detail);
  $('#announcement-map-location').textContent = locations.length > 1 ? `${locations[0].label} 외 ${locations.length - 1}곳` : locations[0].label;
  $('#announcement-map-accuracy').textContent = locations.length > 1 ? '공고에 확인된 모든 공급 위치를 함께 표시합니다.' : locationAccuracyMessage(locations[0]);
  $('#announcement-map-status').textContent = '지도 탭을 선택하면 위치를 불러옵니다.';
  $('#announcement-map-status').dataset.state = 'idle';
  const canvas = $('#announcement-map-canvas');
  canvas.hidden = false;
  canvas.replaceChildren();
  const external = $('#announcement-map-external');
  setOfficialLink(external, kakaoMapSearchUrl(announcementMapSearchQuery(item, detail)), '카카오맵에서 크게 보기', listValue(item.title));
  updateDrawerTabOrientation();
  activateDrawerTab('detail');
}

const EVENT_LABELS={ANNOUNCEMENT:'공고',APPLICATION:'청약 신청',DOCUMENT_REVIEW:'서류 심사',WINNER_ANNOUNCEMENT:'당첨자 발표',CONTRACT:'계약',MOVE_IN:'입주'};
const EVENT_STATUS_LABELS={CONFIRMED:'확정',EXPECTED:'예정',INFERRED:'추정'};
const ORGANIZATION_LABELS={DEVELOPER:'시행사',LANDLORD:'임대사업자',CONTRACTOR:'시공사',OPERATOR:'운영사'};
const ELIGIBILITY_LABELS={AGE:'연령',HOUSING:'무주택',INCOME:'소득',ASSET:'자산',VEHICLE:'자동차',REGION:'거주 지역',HOUSEHOLD:'세대 구성',OTHER:'기타'};
const OPERATOR_LABELS={LTE:'이하',GTE:'이상',EQUALS:'같음',BETWEEN:'범위'};

function displayLabel(labels, value, fallback = '기타') { return labels[value] || fallback; }
function eventPeriod(event) { const start = event.startsAt ? shortDate(event.startsAt) : '시작 미기재'; const end = event.endsAt ? shortDate(event.endsAt) : '종료 미기재'; return `${start} ~ ${end}`; }
function roomLabel(room) { return [room.buildingText,room.floorText,room.roomText].filter(hasText).join(' ') || room.sourceText; }

function renderSupplySites(detail) {
  const container = $('#announcement-sites-content');
  container.replaceChildren();
  const sites = Array.isArray(detail?.sites) ? detail.sites : [];
  if (!sites.length) {
    const empty = document.createElement('p');
    empty.className = 'drawer-sites-empty';
    empty.textContent = '정리된 공급 주택 정보가 없습니다. 공식 공고문에서 확인하세요.';
    container.append(empty);
    return;
  }
  sites.forEach((site, siteIndex) => {
    const section = document.createElement('section');
    section.className = 'drawer-site';
    const heading = document.createElement('h3');
    heading.textContent = site.name || site.location?.displayLabel || `공급 위치 ${siteIndex + 1}`;
    const address = document.createElement('p');
    address.className = 'muted drawer-site-address';
    address.textContent = site.location?.address || [site.location?.region1, site.location?.region2, site.location?.region3].filter(hasText).join(' ') || '위치 상세는 공고문 확인';
    section.append(heading, address);
    if (site.organizations?.length) {
      const organizations = document.createElement('p');
      organizations.className = 'drawer-site-organizations';
      organizations.textContent = site.organizations.map((organization) => `${displayLabel(ORGANIZATION_LABELS,organization.role,'기관')}: ${organization.organizationName}`).join(' · ');
      section.append(organizations);
    }
    for (const unit of site.units || []) {
      const unitBlock = document.createElement('div');
      unitBlock.className = 'drawer-unit';
      const unitHeading = document.createElement('h4');
      const area = hasText(unit.exclusiveAreaM2) && Number.isFinite(Number(unit.exclusiveAreaM2)) ? `${Number(unit.exclusiveAreaM2).toLocaleString('ko-KR')}㎡` : '면적 공고문 확인';
      unitHeading.textContent = [unit.categoryText, unit.unitType, area].filter(hasText).join(' · ');
      unitBlock.append(unitHeading);
      const unitMeta = document.createElement('p');
      unitMeta.className = 'drawer-unit-meta';
      const rooms = (unit.rooms || []).map(roomLabel).filter(hasText);
      unitMeta.textContent = [`공급 ${Number.isFinite(Number(unit.supplyCount)) ? `${unit.supplyCount}호` : '호수 미기재'}`, rooms.length ? `호실 ${rooms.join(', ')}` : null].filter(Boolean).join(' · ');
      unitBlock.append(unitMeta);
      const options = document.createElement('div');
      options.className = 'drawer-rental-options';
      for (const option of unit.rentalOptions || []) {
        const row = document.createElement('div');
        row.className = 'drawer-rental-option';
        const condition = document.createElement('span');
        condition.textContent = option.conditionText || '임대 조건';
        const cost = document.createElement('strong');
        cost.textContent = `보증금 ${formatWon(option.depositWon) || NEEDS_NOTICE} · 월세 ${formatWon(option.monthlyRentWon) || NEEDS_NOTICE}`;
        row.append(condition, cost);
        options.append(row);
      }
      if (!options.childElementCount) {
        const empty = document.createElement('p'); empty.className = 'muted'; empty.textContent = '임대 조건은 공고문에서 확인하세요.'; options.append(empty);
      }
      unitBlock.append(options); section.append(unitBlock);
    }
    container.append(section);
  });
}

function renderEligibilityRules(detail) {
  const container = $('#announcement-eligibility-content');
  container.replaceChildren();
  const rules = Array.isArray(detail?.eligibilityRules) ? detail.eligibilityRules : [];
  if (!rules.length) {
    const empty = document.createElement('p');
    empty.className = 'drawer-sites-empty';
    empty.textContent = '정리된 신청 조건이 없습니다. 공식 공고문에서 확인하세요.';
    container.append(empty);
    return;
  }
  const list = document.createElement('dl');
  list.className = 'eligibility-rule-list';
  rules.forEach((rule) => {
    const row = document.createElement('div');
    const term = document.createElement('dt');
    const description = document.createElement('dd');
    term.textContent = displayLabel(ELIGIBILITY_LABELS, rule.category);
    const hasThreshold = rule.thresholdValue !== null && rule.thresholdValue !== undefined && rule.thresholdValue !== '' && Number.isFinite(Number(rule.thresholdValue));
    const details = [rule.targetGroup, rule.rank ? `${rule.rank}순위` : null, rule.householdSize ? `${rule.householdSize}인 가구` : null, hasThreshold ? `${Number(rule.thresholdValue).toLocaleString('ko-KR')}${rule.thresholdUnit || ''} ${displayLabel(OPERATOR_LABELS,rule.operator,'')}`.trim() : null, rule.description].filter(hasText);
    description.textContent = details.join(' · ') || '공고문 확인';
    row.append(term,description); list.append(row);
  });
  container.append(list);
}

function appendEventTimeline(container, detail) {
  const events = Array.isArray(detail?.events) ? detail.events : [];
  if (!events.length) return;
  const section = document.createElement('section');
  section.className = 'drawer-events';
  const heading = document.createElement('h3'); heading.textContent = '주요 일정'; section.append(heading);
  const list = document.createElement('dl');
  events.forEach((event) => {
    const row = document.createElement('div');
    const term = document.createElement('dt'); term.textContent = displayLabel(EVENT_LABELS,event.eventType,'일정');
    const description = document.createElement('dd'); description.textContent = `${eventPeriod(event)} · ${displayLabel(EVENT_STATUS_LABELS,event.status,'확인 필요')}${event.description ? ` · ${event.description}` : ''}`;
    row.append(term,description); list.append(row);
  });
  section.append(list); container.append(section);
}

function openAnnouncementDrawer(item, detail, trigger) {
  drawerTrigger = trigger;
  const header = $('#announcement-drawer-header');
  const content = $('#announcement-drawer-content');
  header.replaceChildren();
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
  drawerFact(facts, '접수 기간', applicationSchedule(item, detail));
  drawerFact(facts, '지역', listLocation(item, detail));
  if (detail) {
    const options = verifiedCostOptions(detail);
    if (options.length === 1) {
      drawerFact(facts, '보증금', formatWon(options[0].depositWon) || NEEDS_NOTICE);
      drawerFact(facts, '월세', formatWon(options[0].monthlyRentWon) || NEEDS_NOTICE);
    }
    drawerFact(facts, '전용면적', exclusiveArea(detail));
    drawerFact(facts, '입주 예정', hasText(detail.moveInText) ? detail.moveInText : NEEDS_NOTICE);
  } else {
    drawerFact(facts, '보증금', NEEDS_NOTICE);
    drawerFact(facts, '월세', NEEDS_NOTICE);
    drawerFact(facts, '전용면적', NEEDS_NOTICE);
    drawerFact(facts, '입주 예정', NEEDS_NOTICE);
  }
  const note = document.createElement('p');
  note.className = 'drawer-note';
  note.textContent = detail
    ? '신청 자격과 세부 조건은 공식 공고문에서 별도로 확인하세요.'
    : '비용·전용면적 등 상세 조건은 목록에 확인되지 않았습니다. 공고문에서 상세 확인 필요';
  header.append(status, title, source);
  content.append(facts);
  if (detail) appendCostOptions(content, detail);
  if (detail) appendEventTimeline(content, detail);
  content.append(note);
  renderSupplySites(detail);
  renderEligibilityRules(detail);
  const officialUrl = detail?.sourceUrl || item.officialUrl;
  const actions = $('#announcement-drawer-actions');
  actions.replaceChildren();
  actions.append(favoriteButton(item, detail));
  if (hasText(officialUrl)) {
    const link = document.createElement('a');
    link.className = 'official drawer-official';
    setOfficialLink(link, officialUrl, '공식 공고문 보기', listValue(item.title));
    actions.append(link);
  }
  resetDrawerMap(item, detail);
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
  button.dataset.announcementId = item.id || '';
  button.textContent = '상세 보기';
  button.setAttribute('aria-label', `${listValue(item.title)} 상세 보기`);
  button.addEventListener('click', () => openAnnouncementDrawer(item, detail, button));
  return button;
}

function optionValues(items, field) {
  return [...new Set(items.map((item) => item[field]).filter((value) => !isListMissing(value)))].sort((a, b) => String(a).localeCompare(String(b), 'ko'));
}

function regionOptionValues(items) {
  return [...new Set(items.map((item) => listValue(item.area)).filter((value) => value !== MISSING_LIST_VALUE))]
    .sort((a, b) => String(a).localeCompare(String(b), 'ko'));
}

function setSelectOptions(selector, values, selected = $(selector).value) {
  const select = $(selector);
  const labels = { 'announcement-source': '모든 출처', 'announcement-status': '모든 상태', 'announcement-region': '모든 지역', 'announcement-housing-type': '모든 주택 유형' };
  select.replaceChildren(new Option(labels[select.id] || '전체', ''));
  for (const value of values) select.add(new Option(value, value));
  if (selected && !values.includes(selected)) select.add(new Option(selected, selected));
  select.value = selected;
}

let regionHierarchy = [];
function regionValues(level, sido = '', sigungu = '') {
  return [...new Set(regionHierarchy
    .filter(item => (!sido || item.sido === sido) && (!sigungu || item.sigungu === sigungu))
    .map(item => item[level]).filter(hasText))].sort((a, b) => a.localeCompare(b, 'ko'));
}
function replaceRegionOptions(selector, label, values, selected = '', disabled = false) {
  const select = $(selector); select.replaceChildren(new Option(label, ''));
  values.forEach(value => select.add(new Option(value, value)));
  if (selected && values.includes(selected)) select.value = selected;
  select.disabled = disabled;
}
function syncRegionSelects(selected = '') {
  const matching = regionHierarchy.find(item => [item.dong, item.sigungu, item.sido].includes(selected));
  const sido = $('#announcement-region-sido').value || matching?.sido || '';
  replaceRegionOptions('#announcement-region-sido', '전체 시·도', regionValues('sido'), sido);
  const sigungu = $('#announcement-region-sigungu').value || (matching?.sido === sido ? matching?.sigungu : '') || '';
  replaceRegionOptions('#announcement-region-sigungu', sido ? '전체 시·군·구' : '시·도를 먼저 선택', regionValues('sigungu', sido), sigungu, !sido);
  const dong = $('#announcement-region-dong').value || (matching?.sigungu === sigungu ? matching?.dong : '') || '';
  replaceRegionOptions('#announcement-region-dong', sigungu ? '전체 읍·면·동' : '시·군·구를 먼저 선택', regionValues('dong', sido, sigungu), dong, !sigungu);
}
function setupRegionHierarchy(values, selected = '') {
  regionHierarchy = values.filter(item => hasText(item?.sido));
  syncRegionSelects(selected);
}
$('#announcement-region-sido').addEventListener('change', () => {
  $('#announcement-region-sigungu').value = ''; $('#announcement-region-dong').value = ''; syncRegionSelects();
});
$('#announcement-region-sigungu').addEventListener('change', () => { $('#announcement-region-dong').value = ''; syncRegionSelects(); });

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

function syncDraftFilters() {
  catalogState.draftFilters = {
    source: $('#announcement-source').value,
    status: $('#announcement-status').value,
    interestOnly: $('#announcement-interest-only').checked,
    savedOnly: $('#announcement-saved-only').checked,
    includeUnknown: $('#announcement-include-unknown').checked,
    includeClosed: $('#announcement-include-closed').checked,
    supplementalOnly: $('#announcement-supplemental-only').checked,
    query: $('#announcement-query').value.trim(),
    region: $('#announcement-region-dong').value || $('#announcement-region-sigungu').value || $('#announcement-region-sido').value,
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
  $('#announcement-interest-only').checked = filters.interestOnly;
  $('#announcement-saved-only').checked = filters.savedOnly;
  $('#announcement-include-unknown').checked = filters.includeUnknown;
  $('#announcement-include-closed').checked = filters.includeClosed;
  $('#announcement-supplemental-only').checked = filters.supplementalOnly;
  $('#announcement-query').value = filters.query;
  syncRegionSelects(filters.region);
  $('#announcement-housing-type').value = filters.housingType;
  $('#announcement-deposit-max').value = formatNumericValue(filters.depositMaxWon);
  $('#announcement-rent-max').value = formatNumericValue(filters.monthlyRentMaxWon);
  $('#announcement-area-min').value = formatNumericValue(filters.areaMin);
  $('#announcement-area-max').value = formatNumericValue(filters.areaMax);
}

function appliedFilterSummary() {
  const filters = catalogState.appliedFilters;
  const labels = [filters.query, filters.region, filters.housingType, filters.source, filters.status, hasText(filters.depositMaxWon) ? `보증금 ${formatNumericValue(filters.depositMaxWon)}원 이하` : '', hasText(filters.monthlyRentMaxWon) ? `월세 ${formatNumericValue(filters.monthlyRentMaxWon)}원 이하` : '', filters.interestOnly ? '내 관심 조건' : '', filters.savedOnly ? '관심 저장' : '', filters.includeUnknown ? '미기재 포함' : '값 확인된 공고', filters.includeClosed ? '마감 공고 포함' : '마감 공고 제외'].filter(Boolean);
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
  if (state) {
    if (catalogState.appliedFilters.interestOnly) loadAnnouncementPage().catch((error) => showToast({ type: 'error', message: '관심 조건 결과를 갱신하지 못했습니다.', detail: overviewLoadMessage(error) }));
    else renderAllAnnouncements();
    renderHome();
  }
  showToast({ type: 'success', message: '관심 조건을 저장했습니다.' });
  return true;
}

function toggleSavedAnnouncement(item, detail = null) {
  const ids = savedIdsFor(item);
  const saved = isSavedAnnouncement(item);
  const drawerWasOpen = !$('#announcement-drawer').hidden && drawerTrigger?.dataset.announcementId === item.id;
  ids.forEach((id) => {
    if (saved) savedAnnouncementIds.delete(id);
    else savedAnnouncementIds.add(id);
  });
  try { localStorage.setItem(SAVED_STORAGE_KEY, JSON.stringify([...savedAnnouncementIds])); } catch { /* Local storage is optional. */ }
  invalidateAnnouncementRender();
  if (state && catalogState.appliedFilters.savedOnly) loadAnnouncementPage().catch((error) => showToast({ type: 'error', message: '관심 저장 목록을 갱신하지 못했습니다.', detail: overviewLoadMessage(error) }));
  else renderAllAnnouncements();
  if (drawerWasOpen) {
    const replacementTrigger = drawerTrigger?.isConnected
      ? drawerTrigger
      : [...document.querySelectorAll('#all-announcements .open-announcement-detail')].find((candidate) => candidate.dataset.announcementId === item.id);
    openAnnouncementDrawer(item, detail || verifiedDetailFor(item), replacementTrigger || drawerTrigger);
  }
  showToast(saved
    ? { type: 'success', message: '관심 저장을 취소했습니다.' }
    : { type: 'success', message: '관심 저장했습니다.', detail: '전체 공고에서 관심 저장만 필터로 바로 확인할 수 있습니다.', action: { label: '관심 저장 목록으로 이동', run: showSavedAnnouncements } });
}

function favoriteButton(item, detail = null) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'secondary save-announcement';
  button.dataset.announcementId = item.id || '';
  const saved = isSavedAnnouncement(item);
  button.textContent = saved ? '관심 저장됨 · 취소' : '관심 저장';
  button.setAttribute('aria-pressed', String(saved));
  button.addEventListener('click', () => toggleSavedAnnouncement(item, detail));
  return button;
}

function announcementResultCard(item, { badge = '' } = {}) {
  const detail = verifiedDetailFor(item);
  const card = document.createElement('article');
  card.className = `notice-card verified-card ${detail ? 'has-details' : 'needs-details'}`;

  const header = document.createElement('div');
  header.className = 'verified-card-header';
  const source = document.createElement('p');
  source.className = 'source-status single-line-ellipsis';
  source.textContent = [listValue(item.sourceName), listValue(item.housingType)].join(' · ');
  const title = document.createElement('h3');
  title.className = 'multi-line-ellipsis';
  title.textContent = listValue(item.title);
  const kicker = document.createElement('div');
  kicker.className = 'verified-card-kicker';
  kicker.append(source);
  if (badge) {
    const badgeElement = document.createElement('span');
    badgeElement.className = 'new-badge';
    badgeElement.textContent = badge;
    kicker.append(badgeElement);
  }
  header.append(kicker, title);

  const facts = document.createElement('dl');
  facts.className = 'notice-facts';
  fact(facts, '지역', listLocation(item, detail) === MISSING_LIST_VALUE ? '공고문 확인' : listLocation(item, detail));
  fact(facts, '보증금', costForList(detail, 'depositWon'));
  fact(facts, '월세', costForList(detail, 'monthlyRentWon'));
  fact(facts, '전용면적', detail && exclusiveArea(detail) !== NEEDS_NOTICE ? exclusiveArea(detail) : '공고문 확인');
  facts.querySelectorAll('dd').forEach((value) => value.classList.add('multi-line-ellipsis'));

  const meta = document.createElement('div');
  meta.className = 'verified-card-meta';
  for (const [label, value] of [
    ['접수 기간', applicationSchedule(item, detail).replaceAll(NEEDS_NOTICE, '공고문 확인')],
    ['모집 상태', listValue(item.status)],
    ['공급기관', listValue(item.provider)],
  ]) {
    const line = document.createElement('p');
    line.className = 'single-line-ellipsis';
    line.textContent = `${label}: ${value}`;
    meta.append(line);
  }

  const actions = document.createElement('div');
  actions.className = 'verified-card-actions';
  actions.append(drawerButton(item, detail));
  const officialUrl = detail?.sourceUrl || item.officialUrl;
  if (hasText(officialUrl)) {
    const link = document.createElement('a');
    link.className = 'official';
    setOfficialLink(link, officialUrl, '공식 공고문 보기', listValue(item.title));
    actions.append(link);
  }

  const footer = document.createElement('div');
  footer.className = 'verified-card-footer';
  footer.append(meta, actions);

  card.append(header, facts, footer);
  return card;
}

function appendAnnouncementCards(target, items, cardOptions = () => ({})) {
  items.forEach((item) => target.append(announcementResultCard(item, cardOptions(item))));
}

function hasInterestCriteria() {
  return Boolean(interestPreferences.regions?.length)
    || Number.isFinite(interestPreferences.depositMaxWon)
    || Number.isFinite(interestPreferences.monthlyRentMaxWon)
    || Number.isFinite(interestPreferences.areaMin)
    || Number.isFinite(interestPreferences.areaMax);
}

function homeEmptyState(message, action = null) {
  const empty = document.createElement('div');
  empty.className = 'home-empty-state';
  const description = document.createElement('p');
  description.textContent = message;
  empty.append(description);
  if (action) {
    const link = document.createElement('a');
    link.className = 'official';
    link.href = action.href;
    link.textContent = action.label;
    empty.append(link);
  }
  return empty;
}

function recommendedAnnouncements(items) {
  const order = { COMPARABLE: 0, NEEDS_INFO: 1 };
  return deduplicatedAnnouncements(items).map((item, index) => {
    const detail = verifiedDetailFor(item);
    return { item, detail, match: classifyInterest(item, detail, interestPreferences), index };
  }).filter(({ item, detail, match }) => {
    if (isClosedAnnouncement(item, detail) || !(match.kind in order)) return false;
    return match.kind !== 'NEEDS_INFO' || interestPreferences.includeUnknown !== false;
  }).sort((left, right) => order[left.match.kind] - order[right.match.kind]
    || announcementPriority(left.item, left.detail) - announcementPriority(right.item, right.detail)
    || left.index - right.index)
    .slice(0, 3)
    .map(({ item }) => item);
}

function recentAnnouncements(items) {
  return deduplicatedAnnouncements(items).map((item, index) => ({ item, index, date: dateValue(item.announcementDate) }))
    .sort((left, right) => {
      if (left.date && right.date) return right.date.getTime() - left.date.getTime() || left.index - right.index;
      if (left.date) return -1;
      if (right.date) return 1;
      return left.index - right.index;
    })
    .slice(0, 3)
    .map(({ item }) => item);
}

function discoveryIds() {
  return new Set((state.discoveries || []).map((item) => item?.id).filter(Boolean));
}

function renderHome() {
  const items = state.homeAnnouncements || [];
  const recommendations = $('#recommended-announcements');
  const recent = $('#recent-announcements');
  recommendations.replaceChildren();
  recent.replaceChildren();

  if (!hasInterestCriteria()) {
    recommendations.append(homeEmptyState(
      '관심 조건을 설정하면 지역·금액·면적 기준으로 공고를 모아볼 수 있습니다.',
      { href: '#preferences', label: '관심 조건 설정' },
    ));
  } else {
    const matched = recommendedAnnouncements(items);
    if (matched.length) appendAnnouncementCards(recommendations, matched);
    else recommendations.append(homeEmptyState('현재 관심 조건과 비교할 수 있는 공고가 없습니다. 조건을 조정해 보세요.', { href: '#preferences', label: '관심 조건 조정' }));
  }

  const latest = recentAnnouncements(items);
  if (latest.length) {
    const newIds = discoveryIds();
    appendAnnouncementCards(recent, latest, (item) => ({
      badge: savedIdsFor(item).some((id) => newIds.has(id)) ? '신규' : '',
    }));
  } else {
    recent.append(homeEmptyState('표시할 최근 공고가 없습니다.'));
  }
}

function invalidateAnnouncementRender() {
  $('#announcement-loading').hidden = true;
}

function queueAnnouncementAppend() {
  if (!catalogPage.hasNext) return;
  loadAnnouncementPage({ append: true }).catch((error) => showToast({ type: 'error', message: '공고를 더 불러오지 못했습니다.', detail: overviewLoadMessage(error) }));
}

function renderAllAnnouncements(append = false) {
  $('#load-more-announcements').hidden = true;
  const items = catalogPage.items || [];
  $('#announcement-result-count').textContent = catalogPage.totalCount
    ? `${appliedFilterSummary()}검색 결과 ${catalogPage.totalCount.toLocaleString('ko-KR')}건 · ${items.length.toLocaleString('ko-KR')}건 표시`
    : '조건에 맞는 공고가 없습니다. 검색 조건을 조정해 보세요.';

  const target = $('#all-announcements');
  const loading = $('#announcement-loading');
  loading.hidden = true;
  target.classList.remove('announcement-scrollable');

  if (!items.length) {
    target.replaceChildren();
    const message = document.createElement('p');
    message.className = 'muted';
    message.textContent = '표시할 공고가 없습니다.';
    target.append(message);
    return;
  }
  if (!append) {
    target.replaceChildren();
    appendAnnouncementCards(target, items);
  } else {
    const nextItems = items.slice(Math.max(0, items.length - catalogPage.size));
    appendAnnouncementCards(target, nextItems);
  }
  const more = $('#load-more-announcements');
  more.hidden = !catalogPage.hasNext;
  if (!more.hidden) more.textContent = `공고 ${Math.min(catalogPage.size, catalogPage.totalCount - items.length).toLocaleString('ko-KR')}건 더 보기`;
}

function setupAnnouncementCatalog() {
  const facets = state.catalogFacets || {};
  invalidateAnnouncementRender();
  setSelectOptions('#announcement-source', facets.sources || [], catalogState.draftFilters.source);
  $('#announcement-status').value = catalogState.draftFilters.status;
  setupRegionHierarchy(facets.regionHierarchy || [], catalogState.draftFilters.region);
  setSelectOptions('#announcement-housing-type', facets.housingTypes || [], catalogState.draftFilters.housingType);
  writeDraftFilters();
  updateAnnouncementFilterNote();
}

function updateAnnouncementFilterNote() {
  const unknownText = catalogState.appliedFilters.includeUnknown ? '금액·면적이 공고문에만 있는 공고도 함께 표시합니다.' : '지역·금액·면적을 바로 비교할 수 있는 공고만 표시합니다.';
  const closedText = catalogState.appliedFilters.includeClosed ? '마감 공고를 함께 표시합니다.' : '마감 공고는 기본 결과에서 제외되며, 필요하면 마감 공고 포함을 선택할 수 있습니다.';
  $('#announcement-filter-note').textContent = `${unknownText} ${closedText}`;
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

function populateForm(profile) {
  const form = $('#profile-form');
  for (const element of form.elements) {
    if (!element.name) continue;
    const value = profile[element.name];
    const displayValue = Array.isArray(value) ? value.join(', ') : (value !== null && value !== undefined && value !== '' && value !== '미확인' ? value : '');
    element.value = element.matches('[data-numeric-format]') ? formatNumericValue(displayValue) : displayValue;
  }
}

function announcementView() {
  return state ? { scrollTop: $('#all-announcements').scrollTop } : null;
}

function skeletonBlock(className) {
  const block = document.createElement('div');
  block.className = `skeleton ${className}`;
  block.setAttribute('aria-hidden', 'true');
  return block;
}

function showInitialSkeleton() {
  document.querySelector('main').setAttribute('aria-busy', 'true');
  const list = $('#all-announcements');
  list.replaceChildren(...Array.from({ length: 6 }, () => skeletonBlock('skeleton-card')));
  for (const target of [$('#recommended-announcements'), $('#recent-announcements')]) {
    target.replaceChildren(...Array.from({ length: 3 }, () => skeletonBlock('skeleton-card')));
  }
}

function overviewLoadMessage(error) {
  if (error?.name === 'AbortError') return '응답 시간이 오래 걸리고 있습니다. 서버가 실행 중인지 확인한 뒤 다시 시도하세요.';
  return error?.message || '네트워크 상태를 확인한 뒤 다시 시도하세요.';
}

function clearInitialLoadError() {
  const target = $('#overview-load-error');
  target.hidden = true;
  target.replaceChildren();
  document.querySelector('main').removeAttribute('aria-busy');
}

function loadErrorCard(error) {
  const message = document.createElement('div');
  const heading = document.createElement('p');
  heading.textContent = '데이터를 불러오지 못했습니다.';
  const detail = document.createElement('p');
  detail.className = 'muted';
  detail.textContent = overviewLoadMessage(error);
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'secondary';
  retry.textContent = '다시 시도';
  retry.addEventListener('click', reloadPage);
  message.append(heading, detail, retry);
  return message;
}

function initialLoadError(error) {
  document.querySelector('main').removeAttribute('aria-busy');
  const target = $('#overview-load-error');
  target.hidden = false;
  target.replaceChildren(loadErrorCard(error));
  $('#all-announcements').replaceChildren(loadErrorCard(error));
  $('#recommended-announcements').replaceChildren(homeEmptyState('추천 공고를 불러오지 못했습니다.'));
  $('#recent-announcements').replaceChildren(homeEmptyState('최근 공고를 불러오지 못했습니다.'));
}

function showToast({ type, message, detail = '', retry = false, action = null }) {
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
  if (action) {
    const actionButton = document.createElement('button');
    actionButton.type = 'button';
    actionButton.className = 'secondary';
    actionButton.textContent = action.label;
    actionButton.addEventListener('click', () => {
      region.replaceChildren();
      action.run();
    });
    actions.append(actionButton);
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

async function showSavedAnnouncements() {
  catalogState.draftFilters.savedOnly = true;
  catalogState.appliedFilters.savedOnly = true;
  writeDraftFilters();
  window.location.hash = '#announcements';
  await loadAnnouncementPage();
}

async function load() {
  const priorView = announcementView();
  if (!priorView) showInitialSkeleton();
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), OVERVIEW_REQUEST_TIMEOUT_MS);
  let nextState;
  try {
    const response = await fetch('/api/overview', { signal: controller.signal });
    if (!response.ok) throw new Error('저장된 화면 데이터를 불러오지 못했습니다.');
    nextState = await response.json();
  } finally {
    window.clearTimeout(timeout);
  }
  state = nextState;
  clearInitialLoadError();
  setupAnnouncementCatalog();
  await loadAnnouncementPage();
  if (priorView) window.scrollTo({ top: priorView.scrollTop, behavior: 'instant' });
  renderHome();
  renderPrograms(state.programs || []);
  populateForm(state.profile || {});
}

async function reloadPage() {
  try {
    await load();
    showToast({ type: 'success', message: '최신 데이터로 업데이트되었습니다.' });
  } catch (error) {
    if (!state) initialLoadError(error);
    showToast({ type: 'error', message: '데이터를 다시 불러오지 못했습니다.', detail: overviewLoadMessage(error), retry: true });
  }
}

function catalogParams(page) {
  const filters = catalogState.appliedFilters;
  const params = new URLSearchParams({ page: String(page), size: String(ANNOUNCEMENT_PAGE_SIZE), region: filters.region, housingType: filters.housingType, status: filters.status, source: filters.source, query: filters.query, includeClosed: String(filters.includeClosed), supplementalOnly: String(filters.supplementalOnly), includeUnknown: String(filters.includeUnknown), savedOnly: String(filters.savedOnly), sort: 'LATEST' });
  for (const [key, value] of [['depositMaxWon', filters.depositMaxWon], ['rentMaxWon', filters.monthlyRentMaxWon], ['areaMin', filters.areaMin], ['areaMax', filters.areaMax]]) if (hasText(value)) params.set(key, value);
  if (filters.savedOnly) for (const id of savedAnnouncementIds) params.append('savedId', id);
  if (filters.interestOnly) {
    params.set('interestOnly', 'true');
    params.set('interestIncludeUnknown', String(interestPreferences.includeUnknown));
    for (const region of interestPreferences.regions || []) if (hasText(region)) params.append('interestRegion', region);
    for (const [key, value] of [['interestDepositMaxWon', interestPreferences.depositMaxWon], ['interestRentMaxWon', interestPreferences.monthlyRentMaxWon], ['interestAreaMin', interestPreferences.areaMin], ['interestAreaMax', interestPreferences.areaMax]]) if (Number.isFinite(value)) params.set(key, String(value));
  }
  return params;
}

async function loadAnnouncementPage({ append = false } = {}) {
  catalogController?.abort();
  const controller = new AbortController(); catalogController = controller;
  const page = append ? catalogPage.page + 1 : 1;
  const search = $('#apply-announcement-filters'); const more = $('#load-more-announcements');
  search.disabled = true; more.disabled = true; $('#announcement-loading').hidden = false;
  try {
    const response = await fetch(`/api/announcements?${catalogParams(page)}`, { signal: controller.signal });
    if (!response.ok) throw new Error('공고 검색 결과를 불러오지 못했습니다.');
    const result = await response.json(); if (controller.signal.aborted) return;
    catalogPage = { ...result, items: append ? [...catalogPage.items, ...result.items] : result.items };
    renderAllAnnouncements(append);
  } finally {
    if (catalogController === controller) { search.disabled = false; more.disabled = false; $('#announcement-loading').hidden = true; }
  }
}

async function applyAnnouncementFilters() {
  syncDraftFilters();
  const changed = JSON.stringify(catalogState.appliedFilters) !== JSON.stringify(catalogState.draftFilters);
  const button = $('#apply-announcement-filters');
  button.classList.add('is-applying');
  button.setAttribute('aria-busy', 'true');
  if (changed) {
    catalogState.appliedFilters = { ...catalogState.draftFilters };
    updateAnnouncementFilterNote();
  }
  try { await loadAnnouncementPage(); } finally { button.classList.remove('is-applying'); button.removeAttribute('aria-busy'); }
}

async function resetAnnouncementFilters() {
  catalogState.draftFilters = createDefaultCatalogFilters();
  catalogState.appliedFilters = createDefaultCatalogFilters();
  writeDraftFilters();
  updateAnnouncementFilterNote();
  await loadAnnouncementPage();
}
for (const selector of ['#announcement-source', '#announcement-status', '#announcement-interest-only', '#announcement-saved-only', '#announcement-include-unknown', '#announcement-include-closed', '#announcement-supplemental-only', '#announcement-region-sido', '#announcement-region-sigungu', '#announcement-region-dong', '#announcement-housing-type', '#announcement-deposit-max', '#announcement-rent-max', '#announcement-area-min', '#announcement-area-max']) {
  $(selector).addEventListener('change', syncDraftFilters);
}
$('#announcement-query').addEventListener('input', syncDraftFilters);
$('#announcement-query').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    event.preventDefault();
    applyAnnouncementFilters().catch((error) => showToast({ type: 'error', message: '공고를 검색하지 못했습니다.', detail: overviewLoadMessage(error) }));
  }
});
$('#global-search').addEventListener('submit', (event) => {
  event.preventDefault();
  const query = $('#global-query').value.trim();
  $('#announcement-query').value = query;
  catalogState.draftFilters.query = query;
  window.location.hash = '#announcements';
  if (state) applyAnnouncementFilters().catch((error) => showToast({ type: 'error', message: '공고를 검색하지 못했습니다.', detail: overviewLoadMessage(error) }));
  else catalogState.appliedFilters.query = query;
});
$('#apply-announcement-filters').addEventListener('click', () => applyAnnouncementFilters().catch((error) => showToast({ type: 'error', message: '공고를 검색하지 못했습니다.', detail: overviewLoadMessage(error) })));
$('#reset-announcement-filters').addEventListener('click', () => resetAnnouncementFilters().catch((error) => showToast({ type: 'error', message: '공고를 초기화하지 못했습니다.', detail: overviewLoadMessage(error) })));
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
  if (state) {
    if (catalogState.appliedFilters.interestOnly) loadAnnouncementPage().catch((error) => showToast({ type: 'error', message: '관심 조건 결과를 갱신하지 못했습니다.', detail: overviewLoadMessage(error) }));
    else renderAllAnnouncements();
    renderHome();
  }
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
  try {
    const response = await fetch('/api/profile', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data) });
    $('#save-result').textContent = response.ok ? '저장했습니다. 이 메모는 검색 결과에 자동 적용되지 않습니다.' : '저장하지 못했습니다.';
    if (response.ok) await load();
  } catch (error) {
    $('#save-result').textContent = '저장한 뒤 화면을 갱신하지 못했습니다. 다시 시도하세요.';
    showToast({ type: 'error', message: '프로필을 저장하거나 화면을 갱신하지 못했습니다.', detail: overviewLoadMessage(error) });
  }
});

$('#announcement-drawer-close').addEventListener('click', closeAnnouncementDrawer);
$('#announcement-drawer-backdrop').addEventListener('click', closeAnnouncementDrawer);
for (const tab of document.querySelectorAll('[data-drawer-tab]')) {
  tab.addEventListener('click', () => activateDrawerTab(tab.dataset.drawerTab, true));
  tab.addEventListener('keydown', (event) => {
    const tabs = [...document.querySelectorAll('[data-drawer-tab]')];
    const current = tabs.indexOf(event.currentTarget);
    const direction = ['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : ['ArrowLeft', 'ArrowUp'].includes(event.key) ? -1 : 0;
    if (!direction) return;
    event.preventDefault();
    const next = tabs[(current + direction + tabs.length) % tabs.length];
    activateDrawerTab(next.dataset.drawerTab, true);
  });
}
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeAnnouncementDrawer();
  else trapDrawerFocus(event);
});

const VIEW_IDS = ['home', 'announcements', 'preferences', 'programs'];

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
  document.title = `${({ home: '홈', announcements: '공고 찾기', preferences: '관심 조건', programs: '제도 안내' })[activeView]} | 청약 알림`;
  $('#app-navigation').classList.remove('is-open');
  if (activeView === 'preferences') setAreaValidation();
  window.scrollTo({ top: 0, behavior: 'instant' });
}

window.addEventListener('hashchange', () => setActiveView(getViewFromHash()));

loadInterestPreferences();
setActiveView(getViewFromHash());
load().catch((error) => {
  initialLoadError(error);
  showToast({ type: 'error', message: '데이터를 다시 불러오지 못했습니다.', detail: overviewLoadMessage(error), retry: true });
});
