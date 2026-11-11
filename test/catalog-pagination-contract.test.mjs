import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const root=new URL('../',import.meta.url);

test('공고 화면은 개요 응답의 전체 목록 대신 페이지 API를 사용한다',async()=>{
  const source=await readFile(new URL('public/app.js',root),'utf8');
  assert.match(source,/fetch\(`\/api\/announcements\?\$\{catalogParams\(page\)\}`/);
  assert.match(source,/let catalogPage = \{ items: \[\], page: 1/);
  assert.match(source,/catalogController\?\.abort\(\)/);
  assert.match(source,/draftFilters/);
  assert.match(source,/appliedFilters/);
  assert.match(source,/params\.append\('savedId', id\)/);
  assert.match(source,/savedOnly: String\(filters\.savedOnly\)/);
  assert.match(source,/params\.set\('interestOnly', 'true'\)/);
  assert.match(source,/includeUnknown: String\(filters\.includeUnknown\)/);
  assert.doesNotMatch(source,/clientFilteredAnnouncements/);
  assert.match(source,/catalogState\.appliedFilters\.interestOnly\) loadAnnouncementPage\(\)/);
  assert.match(source,/catalogState\.appliedFilters\.savedOnly\) loadAnnouncementPage\(\)/);
  assert.doesNotMatch(source,/state\.allAnnouncements/);
  assert.doesNotMatch(source,/state\.announcementDetails/);
});
