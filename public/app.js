const $ = (selector, root = document) => root.querySelector(selector);
const ANNOUNCEMENT_PAGE_SIZE = 10;
const ANNOUNCEMENT_SCROLL_THRESHOLD = 15;
const MISSING_LIST_VALUE = '목록 미기재';
const NEEDS_NOTICE = '공고문 확인 필요';

let state;
let announcementLimit = ANNOUNCEMENT_PAGE_SIZE;
let announcementObserver;
let announcementLoading = false;
let announcementRenderVersion = 0;
let announcementFrameId = null;
let reloadInFlight = false;
let toastTimer;

function hasText(value) {
  return value != null && String(value).trim() !== '';
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

  for (const joinedItem of joined) {
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
    card.append(schedule, moveIn, verified, link);
    target.append(card);
  }
}

function optionValues(items, field) {
  return [...new Set(items.map((item) => item[field]).filter((value) => !isListMissing(value)))].sort((a, b) => String(a).localeCompare(String(b), 'ko'));
}

function setSelectOptions(selector, values) {
  const select = $(selector);
  const current = select.value;
  select.replaceChildren(new Option(select.id === 'announcement-source' ? '모든 출처' : '모든 상태', ''));
  for (const value of values) select.add(new Option(value, value));
  select.value = values.includes(current) ? current : '';
}

function filteredAnnouncements() {
  const catalog = state.allAnnouncements || { items: [] };
  const source = $('#announcement-source').value;
  const status = $('#announcement-status').value;
  const verifiedOnly = $('#announcement-verified-only').checked;
  const newOnly = $('#announcement-new-only').checked;
  const query = $('#announcement-query').value.trim().toLocaleLowerCase('ko');
  const newIds = newAnnouncementIds();
  return (catalog.items || []).filter((item) => {
    const detail = verifiedDetailFor(item);
    const text = [item.title, item.sourceName, item.provider, item.housingType, item.area, item.status, listLocation(item, detail)].join(' ').toLocaleLowerCase('ko');
    return (!source || item.sourceName === source)
      && (!status || item.status === status)
      && (!verifiedOnly || detail)
      && (!newOnly || newIds.has(item.id))
      && (!query || text.includes(query));
  });
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
  announcementRenderVersion += 1;
  announcementObserver?.disconnect();
  announcementObserver = undefined;
  if (announcementFrameId !== null) window.cancelAnimationFrame(announcementFrameId);
  announcementFrameId = null;
  announcementLoading = false;
  $('#announcement-loading').hidden = true;
}

function queueAnnouncementAppend(renderVersion) {
  if (announcementLoading || renderVersion !== announcementRenderVersion) return;
  const total = filteredAnnouncements().length;
  if (announcementLimit >= total) return;
  announcementLoading = true;
  $('#announcement-loading').hidden = false;
  announcementFrameId = window.requestAnimationFrame(() => {
    announcementFrameId = null;
    if (renderVersion !== announcementRenderVersion) return;
    const currentTotal = filteredAnnouncements().length;
    if (announcementLimit < currentTotal) {
      announcementLimit = Math.min(announcementLimit + ANNOUNCEMENT_PAGE_SIZE, currentTotal);
      renderAllAnnouncements(true, renderVersion);
    }
    if (renderVersion === announcementRenderVersion) announcementLoading = false;
  });
}

function setupAnnouncementObserver(hasMore, renderVersion) {
  announcementObserver?.disconnect();
  const target = $('#all-announcements');
  const sentinel = $('#announcement-scroll-sentinel', target);
  const more = $('#load-more-announcements');
  more.hidden = true;
  if (!hasMore) return;
  if (!('IntersectionObserver' in window)) {
    more.hidden = false;
    more.textContent = `공고 ${Math.min(ANNOUNCEMENT_PAGE_SIZE, filteredAnnouncements().length - announcementLimit).toLocaleString('ko-KR')}건 더 보기`;
    return;
  }
  announcementObserver = new IntersectionObserver((entries) => {
    if (!entries.some((entry) => entry.isIntersecting)) return;
    queueAnnouncementAppend(renderVersion);
  }, { root: target, rootMargin: '0px 0px 240px 0px', threshold: 0.01 });
  if (sentinel) announcementObserver.observe(sentinel);
}

function renderAllAnnouncements(append = false, renderVersion = announcementRenderVersion) {
  if (append && renderVersion !== announcementRenderVersion) return;
  announcementObserver?.disconnect();
  $('#load-more-announcements').hidden = true;
  const catalog = state.allAnnouncements || { items: [] };
  const items = catalog.items || [];
  const visible = filteredAnnouncements();
  const shown = visible.slice(0, announcementLimit);
  const heading = catalog.mode === 'full' ? '전체 목록' : (catalog.mode || '아직 수집 전');
  $('#announcement-count').textContent = `${heading} · 마지막 성공 수집 ${formatDate(catalog.checkedAt)}`;
  $('#announcement-result-count').textContent = `검색 결과 ${visible.length.toLocaleString('ko-KR')}건 / 전체 ${items.length.toLocaleString('ko-KR')}건${visible.length > shown.length ? ` · ${shown.length.toLocaleString('ko-KR')}건 표시` : ''}`;

  const target = $('#all-announcements');
  const loading = $('#announcement-loading');
  loading.hidden = true;
  target.classList.toggle('announcement-scrollable', visible.length > ANNOUNCEMENT_SCROLL_THRESHOLD);

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
    message.textContent = '화면 필터와 일치하는 공고가 없습니다. 필터를 해제하면 전체 수집 공고를 다시 볼 수 있습니다.';
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
  const sentinel = document.createElement('div');
  sentinel.id = 'announcement-scroll-sentinel';
  sentinel.className = 'announcement-scroll-sentinel';
  sentinel.setAttribute('aria-hidden', 'true');
  target.querySelector('#announcement-scroll-sentinel')?.remove();
  target.append(sentinel);
  setupAnnouncementObserver(visible.length > shown.length, renderVersion);
}

function setupAnnouncementCatalog(view = null) {
  const items = state.allAnnouncements?.items || [];
  invalidateAnnouncementRender();
  announcementLimit = view ? Math.max(ANNOUNCEMENT_PAGE_SIZE, view.limit) : ANNOUNCEMENT_PAGE_SIZE;
  setSelectOptions('#announcement-source', optionValues(items, 'sourceName'));
  setSelectOptions('#announcement-status', optionValues(items, 'status'));
  const newOnly = $('#announcement-new-only');
  const baseline = newItemsBaselineAt();
  const hasNewItems = newAnnouncementIds().size > 0;
  newOnly.disabled = !hasNewItems;
  if (!hasNewItems) newOnly.checked = false;
  const baselineText = baseline ? `신규는 직전 성공 수집(${formatDate(baseline)})과 비교해 새로 발견된 공고만을 뜻합니다.` : '신규 기준 수집 시각이 없어 신규 필터는 현재 사용할 수 없습니다.';
  $('#announcement-filter-note').textContent = `필터는 화면 표시 기능입니다. 필터를 해제하면 전체 수집 공고를 다시 볼 수 있습니다. ${baselineText}`;
  renderAllAnnouncements();
  if (view) $('#all-announcements').scrollTop = view.scrollTop;
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
  const outcome = monitor.outcome || (failures.length ? 'SUCCEEDED_WITH_WARNINGS' : (lastSuccess ? 'SUCCEEDED' : 'IDLE'));
  const status = $('#collection-status');
  status.replaceChildren();
  const last = document.createElement('p');
  last.textContent = `마지막 성공 수집: ${lastSuccess ? formatDate(lastSuccess) : '아직 없음'}`;
  const result = document.createElement('p');
  result.className = `collection-outcome ${outcome.toLowerCase()}`;
  if (outcome === 'FAILED') result.textContent = '상태: 수집 실패 · 기존 마지막 정상 목록 유지';
  else if (outcome === 'SUCCEEDED_WITH_WARNINGS') result.textContent = '상태: 일부 출처 실패 · 해당 출처의 기존 마지막 정상 데이터 유지';
  else if (outcome === 'SUCCEEDED') result.textContent = '상태: 수집 성공';
  else result.textContent = '상태: 수집 기록 없음';
  status.append(last, result);
  if (newCount != null) {
    const discovered = document.createElement('p');
    discovered.textContent = `이번 수집 신규 공고: ${newCount.toLocaleString('ko-KR')}건`;
    status.append(discovered);
  }
  if (failures.length) {
    const failed = document.createElement('p');
    failed.className = 'collection-failures';
    failed.textContent = `실패 출처: ${failures.map((failure) => `${failure.source}${failure.error ? ` (${failure.error})` : ''}`).join(', ')}`;
    status.append(failed);
  }
}

function renderPrograms(programs) {
  $('#programs').innerHTML = programs.map((program) => `
    <article class="program"><h3>${escapeHtml(program.name)}</h3><p>${escapeHtml(program.type)} · ${escapeHtml(program.provider)}</p>
    <p><strong>${escapeHtml(program.status)}</strong></p><p>${escapeHtml(program.cost)}</p>
    <p class="muted">${escapeHtml(program.eligibility)}</p><a href="${escapeAttribute(program.officialUrl)}" target="_blank" rel="noopener noreferrer" aria-label="${escapeAttribute(program.name)} 공식 안내 (새 탭에서 열림)">공식 안내</a></article>`).join('');
}

function renderMonitor(monitor, discoveries) {
  const rows = Object.entries(monitor?.sources || {});
  $('#monitor').innerHTML = monitor ? `<p>마지막 확인: <strong>${formatDate(monitor.lastRunAt)}</strong></p>${rows.map(([id, item]) => `<div class="monitor-row ${item.error ? 'monitor-error' : 'monitor-ok'}"><strong>${escapeHtml(id)}</strong><br>${item.error ? escapeHtml(item.error) : `정상 확인 · ${item.recordCount || 0}건`}</div>`).join('')}` : '<p class="muted">아직 실행하지 않았습니다. 지금 공고 수집을 눌러 공식 목록을 확인하세요.</p>';
  $('#discoveries').innerHTML = discoveries.length ? `<div class="discovery"><strong>새로 발견된 공고 ${discoveries.length}건</strong>${discoveries.slice(0, 8).map((item) => `<p><a target="_blank" rel="noopener noreferrer" aria-label="${escapeAttribute(item.name)} 공식 공고문 (새 탭에서 열림)" href="${escapeAttribute(item.officialUrl)}">${escapeHtml(item.name)}</a><br><span class="muted">${escapeHtml(item.reason || '직전 성공 수집과 비교해 새로 발견')}</span></p>`).join('')}</div>` : '';
}

function escapeHtml(value) { return String(value || '').replace(/[&<>\"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char])); }
function escapeAttribute(value) { return escapeHtml(value).replace(/'/g, '&#39;'); }

function populateForm(profile) {
  const form = $('#profile-form');
  for (const element of form.elements) {
    if (!element.name) continue;
    const value = profile[element.name];
    element.value = Array.isArray(value) ? value.join(', ') : (value && value !== '미확인' ? value : '');
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
  $('#summary').textContent = `목표 입주: ${state.profile.targetMoveBy}. 전체 공고는 조건과 무관하게 수집하며, 신청 가능 여부는 원문을 보고 직접 판단하세요.`;
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
  collectionButton.textContent = busy ? '수집 요청 중…' : '지금 공고 수집';
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
    setCollectionRequest(collectionSummary(job));
  } catch (error) {
    setCollectionRequest(`수집 상태를 확인하지 못했습니다: ${error.message}`);
  }
}

collectionButton.addEventListener('click', async () => {
  setCollectionRequest('수집 요청을 보냈습니다. 공식 목록을 수집 중입니다.', true);
  try {
    const response = await fetch('/api/monitor/refresh', { method: 'POST' });
    if (response.status === 429) {
      setCollectionRequest('최근 수집 직후에는 10분 뒤 다시 요청할 수 있습니다.');
      return;
    }
    if (response.status !== 202 && response.status !== 409) throw new Error('수집을 시작하지 못했습니다.');
    await trackCollection();
  } catch (error) {
    setCollectionRequest(`수집을 시작하지 못했습니다: ${error.message}`);
  }
});

function resetAnnouncementLimit() {
  invalidateAnnouncementRender();
  announcementLimit = ANNOUNCEMENT_PAGE_SIZE;
  renderAllAnnouncements();
}
$('#announcement-source').addEventListener('change', resetAnnouncementLimit);
$('#announcement-status').addEventListener('change', resetAnnouncementLimit);
$('#announcement-verified-only').addEventListener('change', resetAnnouncementLimit);
$('#announcement-new-only').addEventListener('change', resetAnnouncementLimit);
$('#announcement-query').addEventListener('input', resetAnnouncementLimit);
$('#load-more-announcements').addEventListener('click', () => {
  queueAnnouncementAppend(announcementRenderVersion);
});

$('#profile-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  data.preferredAreas = data.preferredAreas ? data.preferredAreas.split(',').map((item) => item.trim()).filter(Boolean) : [];
  for (const key of ['maxMonthlyRent', 'maxDeposit']) data[key] = data[key] ? Number(data[key]) : null;
  const response = await fetch('/api/profile', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data) });
  $('#save-result').textContent = response.ok ? '저장했습니다. 이 메모는 공고 수집·표시에는 적용하지 않습니다.' : '저장하지 못했습니다.';
  if (response.ok) await load();
});

load().catch((error) => {
  $('#summary').textContent = `대시보드를 불러오지 못했습니다: ${error.message}`;
  initialLoadError(error);
  showToast({ type: 'error', message: '데이터를 다시 불러오지 못했습니다.', detail: error.message, retry: true });
});
