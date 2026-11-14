import test from 'node:test';
import assert from 'node:assert/strict';

test('catalog filter module is present for React filter restoration', async () => {
  const source = await import('node:fs/promises').then(fs => fs.readFile(new URL('../web/src/features/announcements/catalogFilters.ts', import.meta.url), 'utf8'));
  for (const key of ['areaMin', 'areaMax', 'includeClosed', 'includeUnknown', 'deadlineSoonOnly', 'interestOnly', 'savedOnly', 'reminderDueOnly', 'supplementalOnly', 'savedId', 'sido: _sido', 'sigungu: _sigungu', 'dong: _dong', 'moveSavedAnnouncementId', 'savedAnnouncementNotes', 'saveSavedAnnouncementNote', 'savedAnnouncementReminders', 'saveSavedAnnouncementReminder', 'dueSavedAnnouncementIds', 'SAVED_SEARCHES_STORAGE_KEY', 'savedSearchFilters', 'savedSearches', 'saveSearch', 'deleteSearch', "sort: filters.savedOnly === 'true' ? 'SAVED'"]) assert.ok(source.includes(key));
  const form=await import('node:fs/promises').then(fs => fs.readFile(new URL('../web/src/features/announcements/AnnouncementFilterForm.tsx', import.meta.url), 'utf8'));
  assert.match(form,/formattedDigits/);
  assert.match(form,/unitInput/);
  const reasons=await import('node:fs/promises').then(fs => fs.readFile(new URL('../web/src/features/announcements/matchReasons.ts', import.meta.url), 'utf8'));
  for (const key of ['지역 조건 충족', '전용면적 조건 충족', '임대조건 충족', '추가모집 공고']) assert.ok(reasons.includes(key));
  const empty=await import('node:fs/promises').then(fs => fs.readFile(new URL('../web/src/features/announcements/EmptySearchGuidance.tsx', import.meta.url), 'utf8'));
  for (const key of ['정보 미확인 공고 포함', '마감 공고 포함', '조건 전체 초기화', '아직 관심 저장한 공고가 없습니다.', '공고 찾기로 이동', '확인 시점이 지난 관심 저장 공고가 없습니다.', '마감이 임박한 공고가 없습니다.']) assert.ok(empty.includes(key));
  const share=await import('node:fs/promises').then(fs => fs.readFile(new URL('../web/src/features/announcements/searchShare.ts', import.meta.url), 'utf8'));
  for (const key of ['전체 공고 검색', 'navigator.clipboard', 'document.execCommand']) assert.ok(share.includes(key));
  const deadline=await import('node:fs/promises').then(fs => fs.readFile(new URL('../web/src/features/announcements/savedDeadline.ts', import.meta.url), 'utf8'));
  for (const key of ['오늘 마감', '마감 D-', 'remaining > 3']) assert.ok(deadline.includes(key));
});
