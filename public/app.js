const $ = (selector, root = document) => root.querySelector(selector);
let state;

function formatDate(value) {
  if (!value) return '미확인';
  return String(value).replace('T', ' ');
}

function renderNotices(notices) {
  const target = $('#notices');
  target.innerHTML = '';
  for (const notice of notices) {
    const node = $('#notice-template').content.cloneNode(true);
    $('.status', node).textContent = `${notice.status} · ${notice.priority}`;
    $('h3', node).textContent = notice.name;
    $('.meta', node).textContent = `${notice.housingType} | ${notice.area} | 접수 ${formatDate(notice.applicationStart)} ~ ${formatDate(notice.applicationEnd)}`;
    $('.cost', node).textContent = `비용: ${notice.cost}`;
    $('.move', node).textContent = `입주: ${notice.moveIn}`;
    $('.fit', node).textContent = `일치: ${(notice.matched || []).join(', ') || '확인된 항목 없음'}`;
    $('.need', node).textContent = `추가 확인: ${(notice.needsConfirmation || []).join(', ') || '없음'}`;
    $('.documents', node).textContent = `서류: ${(notice.documents || []).join(', ')}`;
    $('.official', node).href = notice.officialUrl;
    target.append(node);
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
  const query = $('#announcement-query').value.trim().toLocaleLowerCase('ko');
  const visible = items.filter((item) => {
    const text = [item.title, item.sourceName, item.provider, item.housingType, item.area, item.status].join(' ').toLocaleLowerCase('ko');
    return (!source || item.sourceName === source) && (!status || item.status === status) && (!query || text.includes(query));
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
    const article = document.createElement('article');
    article.className = 'announcement-row';
    const title = document.createElement('h3');
    const link = document.createElement('a');
    link.href = item.officialUrl;
    link.target = '_blank';
    link.rel = 'noreferrer';
    link.textContent = item.title;
    title.append(link);
    const details = document.createElement('p');
    details.className = 'announcement-details';
    details.textContent = `${item.sourceName} · ${item.housingType} · ${item.area} · ${item.provider}`;
    const dates = document.createElement('p');
    dates.className = 'muted';
    dates.textContent = `공고 ${formatDate(item.announcedAt)} · 목록상 마감 ${formatDate(item.applicationEnd)} · 상태 ${item.status}`;
    article.append(title, details, dates);
    fragment.append(article);
  }
  target.append(fragment);
}

function setupAnnouncementCatalog() {
  const items = state.allAnnouncements?.items || [];
  setSelectOptions('#announcement-source', optionValues(items, 'sourceName'));
  setSelectOptions('#announcement-status', optionValues(items, 'status'));
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
