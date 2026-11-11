import test from 'node:test';
import assert from 'node:assert/strict';
import { announcementMapSearchQuery, describeAnnouncementLocation, describeAnnouncementLocations, kakaoMapSearchUrl, locationAccuracyMessage, mapReadyMessage } from '../public/announcement-map.js';

test('uses a marker only for coordinates explicitly marked as an exact address', () => {
  const location = describeAnnouncementLocation({}, { location: { address: '서울시 관악구 1', latitude: 37.4, longitude: 126.9, accuracy: 'exact_address' } });
  assert.equal(location.kind, 'exact');
  assert.equal(location.radius, 0);
});

test('treats an existing station-like address as a nearby range', () => {
  const location = describeAnnouncementLocation({}, { location: { district: '관악구', address: '신림역' } });
  assert.deepEqual({ kind: location.kind, query: location.query, label: location.label }, { kind: 'nearby', query: '관악구 신림역', label: '신림역 인근' });
  assert.equal(location.latitude, null);
  assert.match(locationAccuracyMessage(location), /인근 범위/);
});

test('uses the catalog area as a broad district and rejects missing sentinels', () => {
  assert.equal(describeAnnouncementLocation({ area: '서울특별시' }).kind, 'district');
  assert.equal(describeAnnouncementLocation({ area: '목록 기재 없음' }).kind, 'unavailable');
});

test('builds a safe fallback query and encoded external Kakao map link', () => {
  const query = announcementMapSearchQuery({ title: '청년 주택 공고', area: '목록 미기재' });
  assert.equal(query, '청년 주택 공고');
  assert.equal(kakaoMapSearchUrl(query), 'https://map.kakao.com/link/search/%EC%B2%AD%EB%85%84%20%EC%A3%BC%ED%83%9D%20%EA%B3%B5%EA%B3%A0');
});

test('builds a natural ready message for nearby and district labels', () => {
  assert.equal(mapReadyMessage({ label: '신림역 인근' }), '신림역 인근 위치를 지도에 표시했습니다.');
  assert.equal(mapReadyMessage({ label: '전북특별자치도 일대' }), '전북특별자치도 일대 위치를 지도에 표시했습니다.');
});

test('keeps every normalized supply site and preserves marker versus range accuracy', () => {
  const locations = describeAnnouncementLocations({}, { sites: [
    { name: '정확한 건물', location: { address: '서울 관악구 테스트로 1', latitude: 37.4, longitude: 126.9, accuracy: 'EXACT' } },
    { name: '역세권', location: { region1: '서울특별시', region2: '중랑구', address: '상봉역', accuracy: 'LANDMARK' } },
  ] });
  assert.deepEqual(locations.map(({ kind }) => kind), ['exact', 'nearby']);
  assert.equal(locations[1].query, '서울특별시 중랑구 상봉역');
});
