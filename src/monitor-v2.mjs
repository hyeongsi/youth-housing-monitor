import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ROOT, absoluteUrl, dateOnly, hash, nowSeoul, readJson, stripHtml, writeJson,
} from './shared.mjs';

const USER_AGENT = 'SeoulYouthHousingMonitor/0.2 (personal local monitor; contact: local-user)';
const RECENT_ONLY = process.argv.includes('--recent');
const PAUSE_MS = 250;

function attribute(html, name) {
  return html.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']*)["']`, 'i'))?.[1] || null;
}

function tableRows(html) {
  return [...html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)].map((match) => match[1]);
}

function tableCells(row) {
  return [...row.matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)]
    .map((match) => stripHtml(match[1]).replace(/^-->,?\s*/, ''));
}

function pageCountFromHtml(html) {
  const pages = [];
  for (const match of html.matchAll(/(?:[?&](?:page|pageIndex|cp)=|(?:goPage|goList|fnPage)\s*\(\s*['"]?)(\d+)/gi)) pages.push(Number(match[1]));
  return Math.max(1, ...pages.filter(Number.isSafeInteger));
}

function pageUrl(source, page) {
  const url = new URL(source.url);
  url.searchParams.set(source.pageParam || 'page', String(page));
  return url.href;
}

function timestampForCompare(value) {
  const match = String(value || '').match(/\d{4}[-.]\d{2}[-.]\d{2}/);
  return match ? match[0].replaceAll('.', '-') : String(value || '');
}

function record(source, key, fields) {
  return {
    id: hash(`${source.id}:${key}`), sourceId: source.id, sourceName: source.name,
    provider: fields.provider || source.name, title: fields.title,
    housingType: fields.housingType || '목록 기재 없음', area: fields.area || '목록 기재 없음',
    announcedAt: fields.announcedAt || null, applicationEnd: fields.applicationEnd || null,
    status: fields.status || '목록 기재 없음', officialUrl: fields.officialUrl, retrievedAt: nowSeoul(),
  };
}

function parseLhList(html, source) {
  const records = [];
  for (const row of tableRows(html)) {
    const anchor = row.match(/<a\b([^>]*\bwrtancInfoBtn\b[^>]*)>([\s\S]*?)<\/a>/i);
    if (!anchor) continue;
    const [panId, ccrCnntSysDsCd, uppAisTpCd, aisTpCd] = ['data-id1', 'data-id2', 'data-id3', 'data-id4'].map((name) => attribute(anchor[1], name));
    const title = stripHtml(anchor[2]).replace(/^-->,?\s*/, '');
    if (!panId || !ccrCnntSysDsCd || !uppAisTpCd || !aisTpCd || !title) continue;
    const url = new URL('https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do');
    url.searchParams.set('panId', panId);
    url.searchParams.set('ccrCnntSysDsCd', ccrCnntSysDsCd);
    url.searchParams.set('uppAisTpCd', uppAisTpCd);
    url.searchParams.set('aisTpCd', aisTpCd);
    url.searchParams.set('mi', '1026');
    const cells = tableCells(row);
    records.push(record(source, panId, {
      title, housingType: cells[1], area: cells[3], announcedAt: cells[4], applicationEnd: cells[5], status: cells[6], officialUrl: url.href,
    }));
  }
  return records;
}

function parseShList(html, source) {
  const records = [];
  for (const row of tableRows(html)) {
    const sequence = row.match(/getDetailView\(\s*['"]?(\d+)/i)?.[1];
    if (!sequence) continue;
    const cells = tableCells(row);
    if (!cells[1]) continue;
    const url = new URL(source.detailUrl);
    url.searchParams.set('seq', sequence);
    records.push(record(source, sequence, { title: cells[1], provider: cells[2], announcedAt: cells[3], officialUrl: url.href }));
  }
  return records;
}

function parseSeoulPublicList(html, source) {
  const records = [];
  for (const row of tableRows(html)) {
    if (!/\btd1\b/i.test(row)) continue;
    const cells = tableCells(row);
    const link = row.match(/<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>[^<]*바로가기/i)?.[1];
    if (!link || !cells[2]) continue;
    const officialUrl = absoluteUrl(link, source.url);
    if (!officialUrl) continue;
    records.push(record(source, officialUrl, {
      title: cells[2], housingType: cells[1], announcedAt: cells[3], applicationEnd: cells[4], status: cells[5], provider: cells[6], officialUrl,
    }));
  }
  return records;
}

function parseYouthSafeList(payload, source, page) {
  const records = (Array.isArray(payload.resultList) ? payload.resultList : []).map((item) => {
    const url = new URL('https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do');
    url.searchParams.set('optn1', item.optn1 || '');
    url.searchParams.set('boardId', item.boardId);
    url.searchParams.set('menuNo', '400008');
    url.searchParams.set('pageIndex', page);
    return record(source, item.boardId, {
      title: item.nttSj,
      housingType: item.optn2 === '1' ? '공공임대' : item.optn2 === '2' ? '민간임대' : '목록 기재 없음',
      area: '목록 기재 없음', announcedAt: item.optn1, applicationEnd: item.optn4,
      status: item.optn5 === '2' ? '추가모집' : item.optn5 || '목록 기재 없음', provider: item.optn3, officialUrl: url.href,
    });
  });
  return { records, totalPages: Number(payload.pagingInfo?.totPage) || 1 };
}

function parserFor(source, html) {
  if (source.kind === 'lh-list') return parseLhList(html, source);
  if (source.kind === 'sh-list') return parseShList(html, source);
  if (source.kind === 'seoul-public-list') return parseSeoulPublicList(html, source);
  return [];
}

async function fetchOfficial(url) {
  const response = await fetch(url, {
    headers: { 'user-agent': USER_AGENT, accept: 'text/html,application/xhtml+xml' }, signal: AbortSignal.timeout(25_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return { body: await response.text(), finalUrl: response.url, status: response.status };
}

async function fetchYouthSafe(source, page) {
  const response = await fetch(source.endpoint, {
    method: 'POST',
    headers: { 'user-agent': USER_AGENT, accept: 'application/json', 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ bbsId: 'BMSR00015', pageIndex: String(page), searchAdresGu: '', searchCondition: '', searchKeyword: '', optn2: '', optn5: '' }),
    signal: AbortSignal.timeout(25_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return { payload: await response.json(), finalUrl: response.url, status: response.status };
}

function normalizeSnapshot(html) {
  return stripHtml(html).replace(/\d[\d,.:\-/]*/g, '#').slice(0, 120_000);
}

function unique(records) {
  return [...new Map(records.map((item) => [item.id, item])).values()];
}

async function pause() {
  await new Promise((resolve) => setTimeout(resolve, PAUSE_MS));
}

async function collectStructured(source) {
  const records = [];
  const seen = new Set();
  const limit = RECENT_ONLY ? 1 : (source.fullPageLimit || 1);
  let expectedPages = RECENT_ONLY ? 1 : (source.scanUntilEmpty ? limit : 1);
  for (let page = 1; page <= Math.min(limit, expectedPages); page += 1) {
    let parsedRecords;
    if (source.kind === 'youth-safe-list') {
      const response = await fetchYouthSafe(source, page);
      const parsed = parseYouthSafeList(response.payload, source, page);
      parsedRecords = parsed.records;
      expectedPages = RECENT_ONLY ? 1 : parsed.totalPages;
    } else {
      const response = await fetchOfficial(page === 1 ? source.url : pageUrl(source, page));
      parsedRecords = parserFor(source, response.body);
      expectedPages = RECENT_ONLY ? 1 : (source.scanUntilEmpty ? limit : pageCountFromHtml(response.body));
    }
    const unseen = parsedRecords.filter((item) => !seen.has(item.id));
    if (page > 1 && unseen.length === 0) break;
    unseen.forEach((item) => seen.add(item.id));
    records.push(...unseen);
    if (page < Math.min(limit, expectedPages)) await pause();
  }
  return unique(records);
}

async function collectSnapshot(source) {
  const response = await fetchOfficial(source.url);
  return { pageHash: hash(normalizeSnapshot(response.body)), finalUrl: response.finalUrl, status: response.status };
}

function stableRecordHash(records) {
  return hash(records.map((item) => [item.id, item.title, item.announcedAt, item.applicationEnd, item.status, item.officialUrl]).sort().join('\n'));
}

async function main() {
  const checkedAt = nowSeoul();
  const [sources, previousState, previousCatalog] = await Promise.all([
    readJson('sources.json', []), readJson('monitor-state.json', { sources: {} }), readJson('all-announcements.json', { items: [] }),
  ]);
  const previouslyKnown = new Map((previousCatalog.items || []).map((item) => [item.id, item]));
  const report = { checkedAt, mode: RECENT_ONLY ? 'recent' : 'full', checked: [], changed: [], newCandidates: [], failures: [] };
  const nextState = { schemaVersion: 2, lastRunAt: checkedAt, mode: report.mode, sources: {} };
  const catalog = [];

  for (const source of sources) {
    try {
      if (source.kind === 'snapshot') {
        const result = await collectSnapshot(source);
        const changed = previousState.sources?.[source.id]?.pageHash !== result.pageHash;
        nextState.sources[source.id] = { checkedAt, ...result, recordCount: 0, mode: 'snapshot' };
        report.checked.push({ source: source.name, changed, recordCount: 0, mode: 'snapshot' });
        if (changed) report.changed.push(source.name);
      } else {
        const records = await collectStructured(source);
        catalog.push(...records);
        const recordHash = stableRecordHash(records);
        const prior = previousState.sources?.[source.id];
        const scanStrategy = source.scanUntilEmpty ? 'until-empty' : 'known-pages';
        const changed = prior?.recordHash !== recordHash;
        const newRecords = prior?.mode === 'full' && prior?.scanStrategy === scanStrategy ? records.filter((item) => !previouslyKnown.has(item.id)) : [];
        nextState.sources[source.id] = { checkedAt, recordHash, recordCount: records.length, mode: report.mode, scanStrategy };
        report.checked.push({ source: source.name, changed, recordCount: records.length, mode: report.mode });
        if (changed) report.changed.push(source.name);
        report.newCandidates.push(...newRecords);
      }
    } catch (error) {
      const message = error.cause ? `${error.message}: ${error.cause.message}` : error.message;
      nextState.sources[source.id] = { checkedAt, error: message };
      report.failures.push({ source: source.name, error: message });
    }
    await pause();
  }

  const items = unique(catalog).sort((a, b) => timestampForCompare(b.announcedAt).localeCompare(timestampForCompare(a.announcedAt)) || a.title.localeCompare(b.title, 'ko'));
  const previousFull = previousCatalog.mode === 'full' ? previousCatalog.items || [] : [];
  const mergedItems = RECENT_ONLY && previousFull.length ? unique([...items, ...previousFull]) : items;
  const catalogDocument = {
    schemaVersion: 1, checkedAt, mode: RECENT_ONLY ? (previousFull.length ? 'full (최근 페이지 재확인)' : 'recent') : 'full', itemCount: mergedItems.length, items: mergedItems,
  };
  const discoveries = report.newCandidates.map((item) => ({
    ...item, name: item.title, detectedAt: checkedAt,
    reason: '개인 조건을 적용하지 않은 공개 목록의 새 행입니다. 자격·비용·마감일은 원문에서 확인하세요.',
  }));
  await Promise.all([
    writeJson('monitor-state.json', nextState), writeJson('all-announcements.json', catalogDocument), writeJson('auto-discovered.json', discoveries),
    writeJson('notifications.json', {
      checkedAt, actionable: discoveries.map((item) => ({ title: item.title, message: item.reason, url: item.officialUrl })), sourceFailures: report.failures,
    }),
  ]);
  await mkdir(path.join(ROOT, 'logs'), { recursive: true });
  await writeFile(path.join(ROOT, 'logs', `monitor-${dateOnly(checkedAt)}.json`), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({ ...report, catalogCount: catalogDocument.itemCount }, null, 2));
}

main().catch((error) => {
  console.error(`Monitor failed: ${error.stack || error.message}`);
  process.exitCode = 1;
});
