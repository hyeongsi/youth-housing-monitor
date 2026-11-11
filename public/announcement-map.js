const MISSING_VALUES = new Set(['', '목록 기재 없음', '목록 미기재', '미확인']);
const coordinateCache = new Map();
let configPromise;
let sdkPromise;

function text(value) {
  const normalized = String(value ?? '').trim();
  return MISSING_VALUES.has(normalized) ? '' : normalized;
}

function number(value) {
  if (value == null || String(value).trim() === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function describeAnnouncementLocation(item = {}, detail = null) {
  const location = detail?.location || {};
  const latitude = number(location.latitude);
  const longitude = number(location.longitude);
  const district = text(location.district) || text(item.area);
  const address = text(location.address);
  const displayLabel = text(location.displayLabel);
  const query = [...new Set([district, address].filter(Boolean))].join(' ');

  if (latitude !== null && longitude !== null && ['exact_address', 'EXACT'].includes(location.accuracy)) {
    return { kind: 'exact', latitude, longitude, query, label: displayLabel || address || district || '공고 위치', radius: 0 };
  }
  if (address) {
    return { kind: 'nearby', latitude, longitude, query, label: displayLabel || `${address} 인근`, radius: 500 };
  }
  if (district) {
    return { kind: 'district', latitude, longitude, query: district, label: displayLabel || `${district} 일대`, radius: 2500 };
  }
  return { kind: 'unavailable', latitude: null, longitude: null, query: '', label: '위치 정보 없음', radius: 0 };
}

export function describeAnnouncementLocations(item = {}, detail = null) {
  const sites = Array.isArray(detail?.sites) ? detail.sites : [];
  const locations = sites.map((site) => {
    const source = site?.location || {};
    return describeAnnouncementLocation(item, { location: {
      district: [source.region1, source.region2, source.region3].filter(text).join(' '),
      address: source.address,
      displayLabel: source.displayLabel || site.name,
      latitude: source.latitude,
      longitude: source.longitude,
      accuracy: source.accuracy,
    } });
  }).filter((location) => location.kind !== 'unavailable');
  return locations.length ? locations : [describeAnnouncementLocation(item, detail)];
}

export function locationAccuracyMessage(location) {
  if (location.kind === 'exact') return '공고에서 확인된 주소 위치입니다.';
  if (location.kind === 'nearby') return '역명이나 랜드마크를 기준으로 한 인근 범위입니다. 정확한 건물 위치는 공식 공고문에서 확인하세요.';
  if (location.kind === 'district') return '구·시·도 정보를 기준으로 한 대략적인 범위입니다. 정확한 위치는 공식 공고문에서 확인하세요.';
  return '공고에 확인 가능한 위치가 없습니다. 공식 공고문을 확인하세요.';
}

export function announcementMapSearchQuery(item = {}, detail = null) {
  const location = describeAnnouncementLocations(item, detail)[0];
  return location.query || text(item.title) || text(item.provider);
}

export function kakaoMapSearchUrl(query) {
  return query ? `https://map.kakao.com/link/search/${encodeURIComponent(query)}` : 'https://map.kakao.com/';
}

export function mapReadyMessage(location) {
  return `${location.label} 위치를 지도에 표시했습니다.`;
}

async function mapConfig() {
  configPromise ||= fetch('/api/config', { headers: { accept: 'application/json' } })
    .then((response) => response.ok ? response.json() : {})
    .catch(() => ({}));
  return configPromise;
}

function loadKakaoMaps(appKey) {
  if (window.kakao?.maps?.services) return Promise.resolve(window.kakao);
  if (!appKey) return Promise.reject(new Error('KAKAO_MAP_APP_KEY_MISSING'));
  sdkPromise ||= new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(appKey)}&autoload=false&libraries=services`;
    script.async = true;
    script.onerror = () => reject(new Error('KAKAO_MAP_SDK_FAILED'));
    script.onload = () => window.kakao?.maps?.load
      ? window.kakao.maps.load(() => resolve(window.kakao))
      : reject(new Error('KAKAO_MAP_SDK_FAILED'));
    document.head.append(script);
  });
  return sdkPromise;
}

function searchCoordinates(kakao, query) {
  const cacheKey = query.replace(/\s+/g, ' ').trim().toLocaleLowerCase('ko');
  if (coordinateCache.has(cacheKey)) return Promise.resolve(coordinateCache.get(cacheKey));
  const services = kakao.maps.services;
  const geocoder = new services.Geocoder();
  const places = new services.Places();
  return new Promise((resolve) => {
    geocoder.addressSearch(query, (addressResults, addressStatus) => {
      if (addressStatus === services.Status.OK && addressResults[0]) {
        const result = { latitude: Number(addressResults[0].y), longitude: Number(addressResults[0].x) };
        coordinateCache.set(cacheKey, result);
        resolve(result);
        return;
      }
      places.keywordSearch(query, (keywordResults, keywordStatus) => {
        const result = keywordStatus === services.Status.OK && keywordResults[0]
          ? { latitude: Number(keywordResults[0].y), longitude: Number(keywordResults[0].x) }
          : null;
        coordinateCache.set(cacheKey, result);
        resolve(result);
      });
    });
  });
}

function setMapStatus(element, message, state = '') {
  element.textContent = message;
  element.dataset.state = state;
}

function showMapFallback(canvas, message) {
  const placeholder = document.createElement('p');
  placeholder.className = 'drawer-map-placeholder';
  placeholder.textContent = message;
  canvas.hidden = false;
  canvas.replaceChildren(placeholder);
}

export async function renderAnnouncementMap({ item, detail, canvas, status, signal }) {
  const locations = describeAnnouncementLocations(item, detail);
  if (locations.every((location) => location.kind === 'unavailable')) {
    showMapFallback(canvas, '표시할 위치 정보가 없습니다.');
    setMapStatus(status, '지도에 표시할 위치가 없습니다. 공식 공고문이나 카카오맵 검색 결과를 확인하세요.', 'unavailable');
    return;
  }

  canvas.hidden = false;
  setMapStatus(status, '지도를 불러오는 중입니다.', 'loading');
  const config = await mapConfig();
  if (signal?.aborted) return;

  let kakao;
  try {
    kakao = await loadKakaoMaps(config.kakaoMapAppKey);
  } catch (error) {
    if (signal?.aborted) return;
    const missingKey = error.message === 'KAKAO_MAP_APP_KEY_MISSING';
    showMapFallback(canvas, missingKey ? '지도 앱 키가 설정되지 않았습니다.' : '지도를 불러오지 못했습니다.');
    setMapStatus(status, missingKey
      ? '지도 앱 키가 설정되지 않았습니다. 아래 링크에서 위치를 검색해 보세요.'
      : '지도를 불러오지 못했습니다. 아래 링크에서 위치를 검색해 보세요.', missingKey ? 'missing-key' : 'error');
    return;
  }
  if (signal?.aborted) return;

  const resolved = [];
  for (const location of locations) {
    const coordinates = location.latitude !== null && location.longitude !== null
      ? { latitude: location.latitude, longitude: location.longitude }
      : await searchCoordinates(kakao, location.query);
    if (coordinates) resolved.push({ location, coordinates });
  }
  if (signal?.aborted) return;
  if (!resolved.length) {
    showMapFallback(canvas, '위치 검색 결과가 없습니다.');
    setMapStatus(status, '위치 검색 결과를 찾지 못했습니다. 아래 링크에서 직접 검색해 보세요.', 'not-found');
    return;
  }

  const first = resolved[0];
  const center = new kakao.maps.LatLng(first.coordinates.latitude, first.coordinates.longitude);
  canvas.replaceChildren();
  const map = new kakao.maps.Map(canvas, { center, level: first.location.kind === 'district' ? 7 : 4 });
  const bounds = new kakao.maps.LatLngBounds();
  for (const { location, coordinates } of resolved) {
    const position = new kakao.maps.LatLng(coordinates.latitude, coordinates.longitude);
    bounds.extend(position);
    if (location.kind === 'exact') {
      new kakao.maps.Marker({ map, position });
    } else {
      new kakao.maps.Circle({ map, center: position, radius: location.radius, strokeWeight: 2, strokeColor: '#3976c5', strokeOpacity: 0.8, fillColor: '#78aef0', fillOpacity: 0.18 });
      const label = document.createElement('span');
      label.className = 'map-range-label';
      label.textContent = location.label;
      new kakao.maps.CustomOverlay({ map, position, content: label, yAnchor: 1.3 });
    }
  }
  map.relayout();
  if (resolved.length > 1) map.setBounds(bounds, 44, 44, 44, 44);
  else map.setCenter(center);
  setMapStatus(status, resolved.length > 1 ? `${resolved.length}개 공급 위치를 지도에 표시했습니다.` : mapReadyMessage(first.location), 'ready');
}
