const $ = (selector, root = document) => root.querySelector(selector);
const button = $('#collect-announcements');
const result = $('#collection-result');
let pollTimer;
let toastTimer;

function hasText(value) {
  return value != null && String(value).trim() !== '';
}

function formatDate(value) {
  return hasText(value) ? String(value).replace('T', ' ') : '기록 없음';
}

function showToast(type, message, detail = '') {
  window.clearTimeout(toastTimer);
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
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'toast-close';
  close.setAttribute('aria-label', '알림 닫기');
  close.textContent = '닫기';
  close.addEventListener('click', () => $('#toast-region').replaceChildren());
  toast.append(icon, content, close);
  $('#toast-region').replaceChildren(toast);
  toastTimer = window.setTimeout(() => $('#toast-region').replaceChildren(), type === 'success' ? 3500 : 7500);
}

function setBusy(busy, message = '') {
  button.disabled = busy;
  button.classList.toggle('is-loading', busy);
  if (busy) button.setAttribute('aria-busy', 'true');
  else button.removeAttribute('aria-busy');
  $('span', button).textContent = busy ? '공식 목록 수집 중…' : '지금 공고 수집';
  if (message) result.textContent = message;
}

function failureList(monitor) {
  if (Array.isArray(monitor?.failedSources) && monitor.failedSources.length) return monitor.failedSources;
  return Object.entries(monitor?.sources || {}).filter(([, source]) => source?.error).map(([source, value]) => ({ source, error: value.error }));
}

function renderMonitor(state) {
  const target = $('#monitor');
  const monitor = state.monitor || {};
  const rows = Object.entries(monitor.sources || {});
  const sourceNames = new Map((state.allAnnouncements?.items || []).filter((item) => hasText(item.sourceId) && hasText(item.sourceName)).map((item) => [item.sourceId, item.sourceName]));
  target.replaceChildren();
  if (!rows.length) {
    const empty = document.createElement('p');
    empty.className = 'muted';
    empty.textContent = '아직 기관별 수집 기록이 없습니다.';
    target.append(empty);
    return;
  }
  rows.forEach(([id, source]) => {
    const row = document.createElement('div');
    row.className = `monitor-row ${source.error ? 'monitor-error' : 'monitor-ok'}`;
    const name = document.createElement('strong');
    name.textContent = sourceNames.get(id) || source.sourceName || id;
    const status = document.createElement('span');
    status.textContent = source.error ? `실패 · ${source.error} · 기존 데이터 유지` : `정상 · ${Number(source.recordCount || 0).toLocaleString('ko-KR')}건`;
    row.append(name, status);
    target.append(row);
  });
}

function renderDiscoveries(discoveries = []) {
  const target = $('#discoveries');
  target.replaceChildren();
  if (!discoveries.length) {
    const empty = document.createElement('p');
    empty.className = 'muted';
    empty.textContent = '최근 수집에서 새로 발견된 공고가 없습니다.';
    target.append(empty);
    return;
  }
  discoveries.slice(0, 20).forEach((discovery) => {
    const item = document.createElement('article');
    item.className = 'admin-discovery';
    const name = document.createElement(hasText(discovery.officialUrl) ? 'a' : 'strong');
    name.textContent = discovery.name || '이름 미기재 공고';
    if (name instanceof HTMLAnchorElement) {
      name.href = discovery.officialUrl;
      name.target = '_blank';
      name.rel = 'noopener noreferrer';
    }
    const reason = document.createElement('p');
    reason.className = 'muted';
    reason.textContent = discovery.reason || '직전 성공 수집과 비교해 새로 발견';
    item.append(name, reason);
    target.append(item);
  });
}

function renderOverview(state) {
  const catalog = state.allAnnouncements || {};
  const monitor = state.monitor || {};
  const failures = failureList(monitor);
  $('#last-success').textContent = formatDate(catalog.checkedAt || monitor.lastSuccessfulAt);
  $('#catalog-count').textContent = `${Number(catalog.itemCount ?? catalog.items?.length ?? 0).toLocaleString('ko-KR')}건`;
  $('#new-count').textContent = `${(state.discoveries || []).length.toLocaleString('ko-KR')}건`;
  const lastOutcome = failures.length ? `일부 출처 실패: ${failures.map((item) => item.source).join(', ')}` : (catalog.checkedAt ? '최근 수집 정상' : '수집 기록 없음');
  if (!button.disabled) result.textContent = lastOutcome;
  renderMonitor(state);
  renderDiscoveries(state.discoveries || []);
}

async function loadOverview() {
  const response = await fetch('/api/overview');
  if (!response.ok) throw new Error('수집 현황을 불러오지 못했습니다.');
  const state = await response.json();
  renderOverview(state);
  const error = $('#admin-load-error');
  error.hidden = true;
  error.replaceChildren();
}

function jobSummary(job) {
  const total = job.catalogCount == null ? '' : `전체 ${Number(job.catalogCount).toLocaleString('ko-KR')}건 · `;
  const added = `신규 ${Number(job.newItemCount || 0).toLocaleString('ko-KR')}건`;
  if (job.status === 'SUCCEEDED') return `수집 성공: ${total}${added}`;
  if (job.status === 'SUCCEEDED_WITH_WARNINGS') return `일부 출처 실패: ${added} · 기존 마지막 정상 데이터 유지`;
  return `수집 실패${job.error ? `: ${job.error}` : ''}`;
}

async function trackCollection(startedAt = Date.now()) {
  if (Date.now() - startedAt >= 10 * 60 * 1000) {
    setBusy(false, '수집이 계속 처리 중일 수 있습니다. 잠시 후 관리자 화면을 다시 확인하세요.');
    return;
  }
  try {
    const response = await fetch('/api/monitor/refresh');
    if (!response.ok) throw new Error('수집 상태를 확인하지 못했습니다.');
    const job = await response.json();
    if (job.status === 'RUNNING' || job.status === 'IDLE') {
      setBusy(true, '공식 목록을 수집 중입니다.');
      pollTimer = window.setTimeout(() => trackCollection(startedAt), 2000);
      return;
    }
    const summary = jobSummary(job);
    setBusy(false, summary);
    await loadOverview();
    if (job.status === 'SUCCEEDED') showToast('success', '공고 수집이 완료되었습니다.', summary);
    else if (job.status === 'SUCCEEDED_WITH_WARNINGS') showToast('warning', '일부 기관의 수집 결과를 확인해 주세요.', summary);
    else showToast('error', '공고 수집에 실패했습니다.', summary);
  } catch (error) {
    setBusy(false, `수집 상태를 확인하지 못했습니다: ${error.message}`);
    showToast('error', '수집 상태를 확인하지 못했습니다.', error.message);
  }
}

button.addEventListener('click', async () => {
  if (button.disabled) return;
  setBusy(true, '수집 요청을 보내는 중입니다.');
  try {
    const response = await fetch('/api/monitor/refresh', { method: 'POST' });
    if (response.status === 429) {
      setBusy(false, '최근 수집 직후에는 10분 뒤 다시 요청할 수 있습니다.');
      showToast('warning', '잠시 후 다시 시도해 주세요.', result.textContent);
      return;
    }
    if (response.status !== 202 && response.status !== 409) throw new Error('수집을 시작하지 못했습니다.');
    await trackCollection();
  } catch (error) {
    setBusy(false, `수집을 시작하지 못했습니다: ${error.message}`);
    showToast('error', '수집을 시작하지 못했습니다.', error.message);
  }
});

window.addEventListener('beforeunload', () => window.clearTimeout(pollTimer));

loadOverview().catch((error) => {
  const target = $('#admin-load-error');
  target.hidden = false;
  target.textContent = `${error.message} 서버 상태를 확인한 뒤 다시 시도하세요.`;
  result.textContent = '관리자 데이터를 불러오지 못했습니다.';
});
