import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyInterest, searchMatches } from '../public/announcement-explorer.js';

const preference = { regions: ['관악구'], depositMaxWon: 100_000_000, monthlyRentMaxWon: 500_000, areaMin: 15, areaMax: 30 };
const item = { title: '청년안심주택', area: '목록 기재 없음', sourceName: '청년안심주택' };

test('does not create a candidate by combining different deposit and rent pairs', () => {
  const detail = { location: { district: '관악구' }, exclusiveAreaText: '20㎡', costOptions: [
    { depositWon: 80_000_000, monthlyRentWon: 600_000 }, { depositWon: 120_000_000, monthlyRentWon: 400_000 },
  ] };
  assert.equal(classifyInterest(item, detail, preference).kind, 'NONE');
});

test('marks an explicit matching location with complete comparable facts', () => {
  const detail = { location: { district: '관악구', address: '신림동' }, exclusiveAreaText: '20㎡', costOptions: [{ depositWon: 90_000_000, monthlyRentWon: 450_000 }] };
  const result = classifyInterest(item, detail, preference);
  assert.equal(result.kind, 'COMPARABLE');
  assert.ok(result.reasons.includes('보증금 기준 안'));
});

test('does not infer a region from a title and supports AND search terms', () => {
  assert.equal(classifyInterest({ ...item, title: '관악 청년안심' }, null, preference).kind, 'NEEDS_INFO');
  assert.equal(searchMatches({ ...item, title: '청년안심 관악' }, null, '청년안심 관악'), true);
  assert.equal(searchMatches({ ...item, title: '청년안심' }, null, '청년안심 관악'), false);
});

test('does not show a fully known announcement outside the interest limits as needs info', () => {
  const detail = { location: { district: '관악구' }, exclusiveAreaText: '20㎡', costOptions: [{ depositWon: 110_000_000, monthlyRentWon: 450_000 }] };
  assert.equal(classifyInterest(item, detail, preference).kind, 'NONE');
});

test('marks an item with missing comparable facts as needs info for the UI include-unknown group', () => {
  const detail = { location: { district: '관악구' }, costOptions: [{ depositWon: 90_000_000, monthlyRentWon: 450_000 }] };
  assert.equal(classifyInterest(item, detail, preference).kind, 'NEEDS_INFO');
});

test('does not retain an unknown-location item when its known rent exceeds the limit', () => {
  const detail = { exclusiveAreaText: '20㎡', costOptions: [{ depositWon: 90_000_000, monthlyRentWon: 600_000 }] };
  assert.equal(classifyInterest(item, detail, preference).kind, 'NONE');
});
