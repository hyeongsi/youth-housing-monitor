import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ROOT, DATA, absoluteUrl, dateOnly, hash, nowSeoul, readJson, stripHtml, writeJson,
} from './shared.mjs';

const USER_AGENT = 'SeoulYouthHousingMonitor/0.2 (personal local monitor; contact: local-user)';
// Legacy entry point. The active command is monitor-v2.mjs; retain no title-keyword exclusion here either.
const KEYWORDS = /[\s\S]*/;

function extractLinks(html, source) {
  const linkPattern = source.linkPattern ? new RegExp(source.linkPattern, 'i') : null;
  const links = [];
  const expression = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = expression.exec(html))) {
    const hrefMatch = match[1].match(/\bhref\s*=\s*["']([^"']+)["']/i);
    if (!hrefMatch) continue;
    const title = stripHtml(match[2]);
    const url = absoluteUrl(hrefMatch[1], source.url);
    if (!title || !url || /^javascript:/i.test(url)) continue;
    if (linkPattern && !linkPattern.test(url)) continue;
    if (!KEYWORDS.test(title)) continue;
    links.push({
      id: hash(`${source.id}:${url}`),
      sourceId: source.id,
      sourceName: source.name,
      name: title.slice(0, 240),
      officialUrl: url,
      detectedAt: nowSeoul(),
      status: '원문 확인 필요',
      match: '조건 확인 필요',
      reason: '자동 수집된 제목입니다. 보증금·월세·관리비·접수일과 자격은 원문 공고를 확인해야 합니다.',
    });
  }
  return [...new Map(links.map((item) => [item.id, item])).values()].slice(0, 80);
}

async function fetchOfficial(url) {
  const response = await fetch(url, {
    headers: { 'user-agent': USER_AGENT, accept: 'text/html,application/xhtml+xml' },
    signal: AbortSignal.timeout(25_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const body = await response.text();
  return { body, finalUrl: response.url, status: response.status };
}

function summarizePage(html, source) {
  let text = stripHtml(html);
  if (source.id.startsWith('lh-')) {
    const start = text.indexOf('공고문 : 번호');
    const end = text.indexOf('관련사이트', start);
    if (start >= 0) text = text.slice(start, end >= 0 ? end : undefined);
  }
  if (source.id === 'sh-rental-list') {
    const start = text.indexOf('번호');
    const end = text.indexOf('관련 사이트', start);
    if (start >= 0) text = text.slice(start, end >= 0 ? end : undefined);
  }
  // View counts, rotating-banner numbers, and dates change on a read without a
  // new notice. Titles remain, so this avoids false change alerts.
  return text.replace(/\d[\d,.:\-/]*/g, '#').slice(0, 120_000);
}

async function main() {
  const checkedAt = nowSeoul();
  const [sources, previousState, previousItems] = await Promise.all([
    readJson('sources.json', []),
    readJson('monitor-state.json', { sources: {} }),
    readJson('auto-discovered.json', []),
  ]);
  const known = new Map(previousItems.map((item) => [item.id, item]));
  const nextState = { schemaVersion: 1, lastRunAt: checkedAt, sources: {} };
  const report = { checkedAt, checked: [], changed: [], newCandidates: [], failures: [] };

  for (const source of sources) {
    try {
      const { body, finalUrl, status } = await fetchOfficial(source.url);
      const pageHash = hash(summarizePage(body, source));
      const prior = previousState.sources?.[source.id];
      const changed = prior?.pageHash !== pageHash;
      const candidates = source.kind === 'notice-links' ? extractLinks(body, source) : [];
      nextState.sources[source.id] = {
        checkedAt,
        pageHash,
        finalUrl,
        httpStatus: status,
        candidateCount: candidates.length,
      };
      report.checked.push({ source: source.name, changed, candidateCount: candidates.length });
      if (changed) report.changed.push(source.name);
      if (source.kind === 'snapshot') {
        for (const [id, candidate] of known) {
          if (candidate.sourceId === source.id) known.delete(id);
        }
      }
      for (const candidate of candidates) {
        if (!known.has(candidate.id)) {
          known.set(candidate.id, candidate);
          report.newCandidates.push(candidate);
        }
      }
    } catch (error) {
      const message = error.cause ? `${error.message}: ${error.cause.message}` : error.message;
      nextState.sources[source.id] = { checkedAt, error: message };
      report.failures.push({ source: source.name, error: message });
    }
    await new Promise((resolve) => setTimeout(resolve, 700));
  }

  const discoveries = [...known.values()].sort((a, b) => b.detectedAt.localeCompare(a.detectedAt));
  await Promise.all([
    writeJson('monitor-state.json', nextState),
    writeJson('auto-discovered.json', discoveries),
    writeJson('notifications.json', {
      checkedAt,
      actionable: report.newCandidates.map((item) => ({
        title: item.name,
        message: '새 원문 후보가 발견되었습니다. 자격·비용·마감일을 원문에서 확인하세요.',
        url: item.officialUrl,
      })),
      sourceFailures: report.failures,
    }),
  ]);

  await mkdir(path.join(ROOT, 'logs'), { recursive: true });
  await writeFile(path.join(ROOT, 'logs', `monitor-${dateOnly(checkedAt)}.json`), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify(report, null, 2));
}

main().catch((error) => {
  console.error(`Monitor failed: ${error.stack || error.message}`);
  process.exitCode = 1;
});
