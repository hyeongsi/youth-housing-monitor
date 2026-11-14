import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const root=new URL('../',import.meta.url);

test('React 공고 화면은 서버 페이지 API와 URL 적용 조건을 사용한다',async()=>{
  const source=await readFile(new URL('web/src/App.tsx',root),'utf8');
  assert.match(source,/useSearchParams\(\)/);
  assert.match(source,/const \[draft, setDraft\]/);
  assert.match(source,/\/api\/announcements\?\$\{params\}/);
  assert.match(source,/catalogParams\(applied, page, overview\.data\?\.profile \|\| \{\}, savedIds, savedReminders\)/);
  assert.match(source,/setSearch\(searchFromFilters\(next\)\)/);
  assert.match(source,/<AppliedFilterSummary filters=\{applied\} onRemove=\{remove\} onReset=\{reset\}\/>/);
  assert.doesNotMatch(source,/delete next\.sido/);
  assert.match(source,/q\.data\?\.hasNext/);
  assert.match(source,/Number\(q\.data\.page\) !== page/);
  assert.match(source,/남은 공고/);
  assert.match(source,/saved-announcements/);
  assert.match(source,/aria-pressed=\{saved\}/);
  assert.doesNotMatch(source,/allAnnouncements/);
});
