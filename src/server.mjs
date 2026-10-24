import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { ROOT, readJson, writeJson } from './shared.mjs';

const HOST = '127.0.0.1';
const PORT = Number(process.env.PORT || 4173);
const PUBLIC = path.join(ROOT, 'public');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.ico': 'image/x-icon' };

async function overview() {
  const [profile, programs, notices, state, discovered, notifications, allAnnouncements] = await Promise.all([
    readJson('profile.json', {}),
    readJson('programs.json', []),
    readJson('notices.json', []),
    readJson('monitor-state.json', null),
    readJson('auto-discovered.json', []),
    readJson('notifications.json', null),
    readJson('all-announcements.json', { items: [], itemCount: 0, mode: null, checkedAt: null }),
  ]);
  return {
    profile,
    programs,
    notices,
    monitor: state,
    discoveries: discovered,
    notifications,
    allAnnouncements,
  };
}

function send(response, statusCode, body, contentType = 'application/json; charset=utf-8') {
  response.writeHead(statusCode, { 'content-type': contentType, 'cache-control': 'no-store' });
  response.end(typeof body === 'string' ? body : JSON.stringify(body));
}

async function bodyAsJson(request) {
  let body = '';
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 100_000) throw new Error('Request too large');
  }
  return JSON.parse(body || '{}');
}

function profilePatch(input) {
  const allowed = new Set([
    'employmentStatus', 'grossIncomeBand', 'assetBand', 'cashForDepositBand', 'maxMonthlyRent',
    'maxDeposit', 'homeOwnership', 'maritalAndHousehold', 'livesSeparatelyFromParents',
    'vehicleOwnership', 'preferredAreas', 'commuteOrSchoolCondition', 'currentLeaseEnd',
    'earliestMoveDate', 'notes',
  ]);
  return Object.fromEntries(Object.entries(input).filter(([key]) => allowed.has(key)));
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host}`);
    if (url.pathname === '/api/overview' && request.method === 'GET') return send(response, 200, await overview());
    if (url.pathname === '/api/profile' && request.method === 'PUT') {
      const current = await readJson('profile.json', {});
      const update = profilePatch(await bodyAsJson(request));
      const profile = { ...current, ...update, lastUpdated: new Date().toISOString().slice(0, 10) };
      await writeJson('profile.json', profile);
      return send(response, 200, profile);
    }
    const requested = url.pathname === '/' ? '/index.html' : url.pathname;
    const filePath = path.resolve(PUBLIC, `.${requested}`);
    if (!filePath.startsWith(PUBLIC)) return send(response, 403, 'Forbidden', 'text/plain; charset=utf-8');
    const file = await stat(filePath);
    if (!file.isFile()) return send(response, 404, 'Not found', 'text/plain; charset=utf-8');
    response.writeHead(200, { 'content-type': MIME[path.extname(filePath)] || 'application/octet-stream' });
    createReadStream(filePath).pipe(response);
  } catch (error) {
    send(response, error instanceof SyntaxError ? 400 : 500, { error: error.message });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Housing dashboard: http://${HOST}:${PORT}`);
});
