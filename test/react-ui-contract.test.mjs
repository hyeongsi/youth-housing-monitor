import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const root = new URL('../', import.meta.url);

test('React PDF 등록·검토 흐름은 기존 API 계약을 사용한다', async () => {
  const [app, review, sidebar, housing, schedule, eligibility] = await Promise.all([
    readFile(new URL('web/src/App.tsx', root), 'utf8'),
    readFile(new URL('web/src/ReviewComplete.tsx', root), 'utf8'),
    readFile(new URL('web/src/features/document-import/ReviewSidebar.tsx', root), 'utf8'),
    readFile(new URL('web/src/features/document-import/HousingEditor.tsx', root), 'utf8'),
    readFile(new URL('web/src/features/document-import/ScheduleEditor.tsx', root), 'utf8'),
    readFile(new URL('web/src/features/document-import/EligibilityEditor.tsx', root), 'utf8'),
  ]);
  for (const endpoint of ['/api/admin/announcements', '/api/admin/document-parser-options', '/api/admin/document-imports', '/validate', '/commit', '/detail-revisions', '/diff', '/restore']) {
    assert.ok(`${app}\n${review}\n${sidebar}\n${housing}\n${schedule}\n${eligibility}`.includes(endpoint));
  }
  for (const label of ['단지·주택', '임대조건', '일정', '신청 조건', '원문과 같음', '직접 수정함', '운영 화면에 반영']) {
    assert.ok(`${review}\n${sidebar}\n${housing}\n${schedule}\n${eligibility}`.includes(label));
  }
});

test('React 지도는 앱 키 확인과 외부 지도 fallback을 유지한다', async () => {
  const source = await readFile(new URL('web/src/map.ts', root), 'utf8');
  for (const text of ['/api/config', 'kakaoMapAppKey', 'AbortSignal', 'https://map.kakao.com/link/search/']) assert.ok(source.includes(text));
});
