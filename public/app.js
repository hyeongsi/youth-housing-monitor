const $ = (selector, root = document) => root.querySelector(selector);
let state;

function formatDate(value) {
  if (!value) return '미확인';
  return String(value).replace('T', ' ');
}

function shortDate(value) {
  if (!value) return '미확인';
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

function formatWonRange(min, max) {
  const first = formatWon(min);
  const last = formatWon(max);
  if (first && last) return first === last ? first : `${first.replace(' 원', '')} ~ ${last}`;
  return first || last || '미확인';
}

function detailFor(item) {
  const details = state.announcementDetails?.items || {};
  const detail = details[item.id];
  return detail?.verificationStatus === 'VERIFIED' ? detail : null;
}

function locationText(detail) {
  const values = [detail?.location?.district, detail?.location?.address].filter(Boolean);
  return values.length ? values.join(' ') : '미확인';
}

function newAnnouncementIds() {
  return new Set((state.discoveries || []).map((item) => item.id).filter(Boolean));
}

function applicationText(item, detail) {
  const application = detail?.application;
  if (application?.start || application?.end) return `${shortDate(application.start)} ~ ${shortDate(application.end)}`;
  return item.applicationEnd && item.applicationEnd !== '목록 기재 없음' ? `목록상 마감 ${shortDate(item.applicationEnd)}` : '미확인';
}

function noticeDetail(notice) {
  let boardId;
  try { boardId = new URL(notice.officialUrl).searchParams.get('boardId'); } catch (_) { return null; }
  if (!boardId) return null;
  const item = (state.allAnnouncements?.items || []).find((candidate) => {
    try { return new URL(candidate.officialUrl).searchParams.get('boardId') === boardId; } catch (_) { return false; }
  });
  return item ? { item, detail: detailFor(item) } : null;
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

function renderNotices(notices) {
  const target = $('#notices');
  target.replaceChildren();
  for (const notice of notices) {
    const linked = noticeDetail(notice);
    const detail = linked?.detail;
    const card = document.createElement('article');
    card.className = 'notice-card verified-card';
    const header = document.createElement('div');
    header.className = 'card-statuses';
    for (const label of [notice.status || '상태 미확인', notice.provider || '출처 미확인']) {
      const badge = document.createElement('span'); badge.className = 'status'; badge.textContent = label; header.append(badge);
    }
    const verified = document.createElement('span'); verified.className = 'verified-badge'; verified.textContent = '상세 확인 완료'; header.append(verified);
    const title = document.createElement('h3'); title.textContent = notice.name;
    const type = document.createElement('p'); type.className = 'card-type'; type.textContent = notice.housingType || '주택 유형 미확인';
    const facts = document.createElement('dl'); facts.className = 'notice-facts';
    fact(facts, '📍 위치', detail ? locationText(detail) : (notice.area || '미확인'));
    if (detail) {
      fact(facts, '💰 보증금', formatWonRange(detail.cost?.depositMinWon, detail.cost?.depositMaxWon));
      fact(facts, '💳 월세', formatWonRange(detail.cost?.monthlyRentMinWon, detail.cost?.monthlyRentMaxWon));
    } else {
      fact(facts, '💰 비용', notice.cost || '공고문 확인 필요');
    }
    fact(facts, '📅 접수', detail ? applicationText(linked.item, detail) : `${shortDate(notice.applicationStart)} ~ ${shortDate(notice.applicationEnd)}`);
    fact(facts, '🏠 입주', notice.moveIn || '미확인');
    const management = document.createElement('p'); management.className = 'management-fee'; management.textContent = `관리비: ${detail?.cost?.managementFeeText || notice.managementFee || '공고문 확인 필요'}`;
    const checked = document.createElement('p'); checked.className = 'verified-at'; checked.textContent = `확인일: ${detail?.verifiedAt || notice.verifiedAt || '미확인'}`;
    const link = document.createElement('a'); link.className = 'official'; link.href = detail?.sourceUrl || notice.officialUrl; link.target = '_blank'; link.rel = 'noreferrer'; link.textContent = '공식 공고문 보기';
    card.append(header, title, type, facts, management, checked, link);
    target.append(card);
  }
}

function optionValues(items, field) {
  return [...new Set(items.map((item) => item[field]).filter(Boolean))].sort((a, b) => String(a).localeCompare(String(b), 'ko'));
}

function setSelectOptions(selector, values) {
  const select = $(selector);
  const current = select.value;
  select.replaceChildren(new Option(select.id === 'announcement-source' ? '모든 출처' : '모든 상태', ''));
  for (const value of values) select.add(new Option(value, value));
  select.value = values.includes(current) ? current : '';
}

function renderAllAnnouncements() {
  const catalog = state.allAnnouncements || { items: [] };
  const items = catalog.items || [];
  const source = $('#announcement-source').value;
  const status = $('#announcement-status').value;
  const verifiedOnly = $('#announcement-verified-only').checked;
  const newOnly = $('#announcement-new-only').checked;
  const query = $('#announcement-query').value.trim().toLocaleLowerCase('ko');
  const newIds = newAnnouncementIds();
  const visible = items.filter((item) => {
    const detail = detailFor(item);
    const text = [item.title, item.sourceName, item.provider, item.housingType, item.area, item.status, locationText(detail)].join(' ').toLocaleLowerCase('ko');
    return (!source || item.sourceName === source) && (!status || item.status === status) && (!verifiedOnly || detail) && (!newOnly || newIds.has(item.id)) && (!query || text.includes(query));
  });
  const heading = catalog.mode === 'full' ? '전체 목록 스캔' : catalog.mode || '아직 수집 전';
  $('#announcement-count').textContent = `${visible.length.toLocaleString('ko-KR')} / ${items.length.toLocaleString('ko-KR')}건 · ${heading} · ${formatDate(catalog.checkedAt)}`;
  const target = $('#all-announcements');
  target.replaceChildren();
  if (!items.length) {
    const message = document.createElement('p');
    message.className = 'muted';
    message.textContent = '아직 구조화된 목록을 수집하지 않았습니다. 지금 공고 수집을 눌러 공개 목록 전체를 불러올 수 있습니다.';
    target.append(message);
    return;
  }
  if (!visible.length) {
    const message = document.createElement('p');
    message.className = 'muted';
    message.textContent = '화면 필터와 일치하는 공고가 없습니다. 수집 대상에서 제외된 것이 아니라 현재 보기 조건이 좁습니다.';
    target.append(message);
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const item of visible) {
    const detail = detailFor(item);
    const article = document.createElement('article');
    article.className = `announcement-row ${detail ? 'has-details' : 'needs-details'}`;
    const top = document.createElement('div'); top.className = 'announcement-topline';
    const title = document.createElement('h3');
    const link = document.createElement('a');
    link.href = item.officialUrl;
    link.target = '_blank';
    link.rel = 'noreferrer';
    link.textContent = item.title;
    title.append(link);
    const badges = document.createElement('div'); badges.className = 'announcement-badges';
    for (const label of [item.status, newIds.has(item.id) ? '신규' : null, detail ? '상세 확인 완료' : null].filter(Boolean)) {
      const badge = document.createElement('span'); badge.className = label === '상세 확인 완료' ? 'verified-badge' : 'status'; badge.textContent = label; badges.append(badge);
    }
    top.append(title, badges);
    const sourceLine = document.createElement('p'); sourceLine.className = 'announcement-details'; sourceLine.textContent = `${item.sourceName || '출처 미확인'} · ${item.housingType || '주택 유형 미확인'} · ${item.provider || '기관 미확인'}`;
    const facts = document.createElement('dl'); facts.className = 'announcement-facts';
    fact(facts, '위치', locationText(detail));
    fact(facts, '보증금', detail ? formatWonRange(detail.cost?.depositMinWon, detail.cost?.depositMaxWon) : '미확인');
    fact(facts, '월세', detail ? formatWonRange(detail.cost?.monthlyRentMinWon, detail.cost?.monthlyRentMaxWon) : '미확인');
    fact(facts, '접수', applicationText(item, detail));
    fact(facts, '상태', item.status || '미확인');
    const notice = document.createElement('p'); notice.className = detail ? 'detail-confirmed' : 'detail-needed';
    notice.textContent = detail ? `확인일 ${detail.verifiedAt || '미확인'} · 관리비: ${detail.cost?.managementFeeText || '공고문 확인 필요'}` : '공고문에서 상세 확인 필요';
    article.append(top, sourceLine, facts, notice);
    fragment.append(article);
  }
  target.append(fragment);
}

function setupAnnouncementCatalog() {
  const items = state.allAnnouncements?.items || [];
  setSelectOptions('#announcement-source', optionValues(items, 'sourceName'));
  setSelectOptions('#announcement-status', optionValues(items, 'status'));
  const newOnly = $('#announcement-new-only');
  const hasNewItems = newAnnouncementIds().size > 0;
  newOnly.disabled = !hasNewItems;
  if (!hasNewItems) newOnly.checked = false;
  $('#announcement-filter-note').textContent = hasNewItems ? '필터는 화면 표시만 바꾸며 수집 범위나 개인 조건 판정에는 영향을 주지 않습니다.' : '신규 공고 데이터가 없어 신규 필터는 현재 사용할 수 없습니다.';
  renderAllAnnouncements();
}

function renderPrograms(programs) {
  $('#programs').innerHTML = programs.map((program) => `
    <article class="program"><h3>${escapeHtml(program.name)}</h3><p>${escapeHtml(program.type)} · ${escapeHtml(program.provider)}</p>
    <p><strong>${escapeHtml(program.status)}</strong></p><p>${escapeHtml(program.cost)}</p>
    <p class="muted">${escapeHtml(program.eligibility)}</p><a href="${escapeAttribute(program.officialUrl)}" target="_blank" rel="noreferrer">공식 안내</a></article>`).join('');
}

function renderMonitor(monitor, discoveries) {
  const rows = Object.entries(monitor?.sources || {});
  $('#monitor').innerHTML = monitor ? `<p>마지막 확인: <strong>${formatDate(monitor.lastRunAt)}</strong></p>${rows.map(([id, item]) => `<div class="monitor-row ${item.error ? 'monitor-error' : 'monitor-ok'}"><strong>${escapeHtml(id)}</strong><br>${item.error ? escapeHtml(item.error) : `정상 확인 · ${item.recordCount || 0}건`}</div>`).join('')}` : '<p class="muted">아직 실행하지 않았습니다. 지금 공고 수집을 눌러 공식 목록을 확인하세요.</p>';
  $('#discoveries').innerHTML = discoveries.length ? `<div class="discovery"><strong>원문 확인 대기 ${discoveries.length}건</strong>${discoveries.slice(0, 8).map((item) => `<p><a target="_blank" rel="noreferrer" href="${escapeAttribute(item.officialUrl)}">${escapeHtml(item.name)}</a><br><span class="muted">${escapeHtml(item.reason)}</span></p>`).join('')}</div>` : '';
}

function escapeHtml(value) { return String(value || '').replace(/[&<>"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char])); }
function escapeAttribute(value) { return escapeHtml(value).replace(/'/g, '&#39;'); }

function populateForm(profile) {
  const form = $('#profile-form');
  for (const element of form.elements) {
    if (!element.name) continue;
    const value = profile[element.name];
    element.value = Array.isArray(value) ? value.join(', ') : (value && value !== '미확인' ? value : '');
  }
}

async function load() {
  const response = await fetch('/api/overview');
  state = await response.json();
  $('#summary').textContent = `목표 입주: ${state.profile.targetMoveBy}. 전체 공고는 조건과 무관하게 수집하며, 신청 가능 여부는 원문을 보고 직접 판단하세요.`;
  renderNotices(state.notices);
  setupAnnouncementCatalog();
  renderPrograms(state.programs);
  renderMonitor(state.monitor, state.discoveries);
  populateForm(state.profile);
}

$('#reload').addEventListener('click', load);
const collectionButton = $('#collect-announcements');
const collectionResult = $('#collection-result');

function collectionMessage(message, disabled = false) {
  collectionResult.textContent = message;
  collectionButton.disabled = disabled;
}

function collectionSummary(job) {
  const newCount = Number(job.newItemCount || 0).toLocaleString('ko-KR');
  const total = job.catalogCount == null ? null : Number(job.catalogCount).toLocaleString('ko-KR');
  const checked = (job.checked || []).map((item) => `${item.source} ${Number(item.recordCount || 0).toLocaleString('ko-KR')}건`).join(', ');
  const checkedSummary = checked ? ` · ${checked}` : '';
  if (job.status === 'SUCCEEDED') return `수집 완료: ${total ? `전체 ${total}건 · ` : ''}신규 ${newCount}건${checkedSummary}`;
  if (job.status === 'SUCCEEDED_WITH_WARNINGS') {
    const failures = job.failures || [];
    const names = failures.map((item) => item.source).filter(Boolean);
    const warning = names.length > 1 ? `${names[0]} 외 ${names.length - 1}개 출처 확인 실패` : names.length === 1 ? `${names[0]} 출처 확인 실패` : '일부 출처 확인 실패';
    const detail = failures[0]?.error ? ` (${failures[0].error})` : '';
    return `수집 완료: 신규 ${newCount}건 · ${warning}${detail}${checkedSummary}`;
  }
  return '수집을 완료하지 못했습니다. 기존 목록은 유지됩니다.';
}

async function trackCollection(startedAt = Date.now()) {
  if (Date.now() - startedAt >= 10 * 60 * 1000) {
    collectionMessage('수집은 계속될 수 있으니 화면 다시 불러오기로 결과를 확인하세요');
    return;
  }
  try {
    const response = await fetch('/api/monitor/refresh');
    if (!response.ok) throw new Error('수집 상태를 확인하지 못했습니다.');
    const job = await response.json();
    if (job.status === 'RUNNING' || job.status === 'IDLE') {
      collectionMessage('공식 목록을 수집 중입니다. 완료까지 잠시 걸릴 수 있습니다.', true);
      window.setTimeout(() => trackCollection(startedAt), 2000);
      return;
    }
    if (job.status === 'SUCCEEDED' || job.status === 'SUCCEEDED_WITH_WARNINGS') {
      await load();
      collectionMessage(collectionSummary(job));
      return;
    }
    collectionMessage(collectionSummary(job));
  } catch (error) {
    collectionMessage(`수집 상태를 확인하지 못했습니다: ${error.message}`);
  }
}

collectionButton.addEventListener('click', async () => {
  collectionMessage('공식 목록을 수집 중입니다. 완료까지 잠시 걸릴 수 있습니다.', true);
  try {
    const response = await fetch('/api/monitor/refresh', { method: 'POST' });
    if (response.status === 429) {
      collectionMessage('최근 수집 직후에는 10분 뒤 다시 요청할 수 있습니다.');
      return;
    }
    if (response.status !== 202 && response.status !== 409) throw new Error('수집을 시작하지 못했습니다.');
    await trackCollection();
  } catch (error) {
    collectionMessage(`수집을 시작하지 못했습니다: ${error.message}`);
  }
});
$('#announcement-source').addEventListener('change', renderAllAnnouncements);
$('#announcement-status').addEventListener('change', renderAllAnnouncements);
$('#announcement-verified-only').addEventListener('change', renderAllAnnouncements);
$('#announcement-new-only').addEventListener('change', renderAllAnnouncements);
$('#announcement-query').addEventListener('input', renderAllAnnouncements);
$('#profile-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  data.preferredAreas = data.preferredAreas ? data.preferredAreas.split(',').map((item) => item.trim()).filter(Boolean) : [];
  for (const key of ['maxMonthlyRent', 'maxDeposit']) data[key] = data[key] ? Number(data[key]) : null;
  const response = await fetch('/api/profile', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data) });
  $('#save-result').textContent = response.ok ? '저장했습니다. 이 메모는 공고 수집·표시에는 적용하지 않습니다.' : '저장하지 못했습니다.';
  if (response.ok) await load();
});

load().catch((error) => { $('#summary').textContent = `대시보드를 불러오지 못했습니다: ${error.message}`; });
