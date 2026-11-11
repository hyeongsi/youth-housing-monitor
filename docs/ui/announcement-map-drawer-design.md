# 공고 상세 지도 화면 설계

## 목표

공고 상세 Drawer 안에서 `상세 정보`와 `지도`를 전환해 공고 위치를 확인한다. 기존 공고 검색·저장·공식 공고문 동작은 유지한다. 위치가 부정확한 공고를 정확한 건물 위치처럼 표시하지 않는다.

## 현재 구조와 제약

- 상세 Drawer는 `public/index.html`의 `#announcement-drawer`를 `public/app.js`의 `openAnnouncementDrawer()`가 채운다.
- 현재 Drawer 폭은 데스크톱 440px, 모바일 100vw다.
- 전체 공고 2,400건 중 대부분은 시·도 수준의 `area`만 있다.
- 검증 상세 5건의 `location`은 `district`와 역명 형태의 `address`만 있고 위도·경도는 없다.
- Kakao Maps JavaScript SDK를 사용하려면 JavaScript 앱 키와 실행 도메인 등록이 필요하다.

## 화면 구조

### 데스크톱

- Drawer 폭: `min(640px, 100vw)`
- 상단: 상태, 공고명, 출처·주택 유형, 닫기 버튼
- 본문: 88px 왼쪽 탭 레일 + 나머지 패널
  - `상세 정보`
  - `지도`
- 하단 고정 영역: 기존 `관심 저장`, `공식 공고문 보기`
- 지도 패널 안에 `카카오맵에서 크게 보기` 링크를 둔다.

### 모바일

- Drawer는 100vw 전체 화면을 유지한다.
- 왼쪽 탭 레일은 상단 2열 탭으로 변경한다.
- 지도 높이는 약 42dvh, 최소 280px로 제한한다.

## 위치 정확도 규칙

| 위치 데이터 | 정확도 | 표시 방식 |
| --- | --- | --- |
| 좌표와 명시적 `exact_address` | 정확한 위치 | 단일 마커 |
| 역·건물명 또는 기존 상세 `address` | 인근 기준 | 반투명 원형 범위와 중심 라벨 |
| 구·시·도만 존재 | 대략적인 위치 | 넓은 원형 범위와 지역 라벨 |
| 위치 없음 | 확인 불가 | 지도 대신 빈 상태와 공식 공고문 안내 |

- 현재 데이터의 역명은 `nearby_landmark`, 목록의 `area`는 `district`로 취급한다.
- 정확도 정보가 없는 기존 `location.address`를 임의로 `exact_address`로 승격하지 않는다.

향후 상세 데이터가 좌표를 제공하면 다음 형태를 사용한다.

```json
{
  "location": {
    "district": "관악구",
    "address": "신림역",
    "latitude": 37.4842,
    "longitude": 126.9297,
    "accuracy": "nearby_landmark",
    "displayLabel": "신림역 인근"
  }
}
```

## 지도 연동

- 새 UI 프레임워크나 지도 래퍼 라이브러리는 추가하지 않는다.
- Kakao Maps JavaScript SDK를 `지도` 탭을 처음 누를 때 한 번만 lazy-load한다.
- 서버의 `KAKAO_MAP_APP_KEY` 환경변수를 `/api/config`로 전달한다.
- JavaScript 앱 키는 등록 도메인 제한을 전제로 브라우저에 전달하며 REST API 키는 전달하지 않는다.
- 좌표가 없으면 `services.Geocoder.addressSearch()`를 먼저 사용하고, 실패하면 `Places.keywordSearch()`를 사용한다.
- 검색 결과가 여러 개인 경우 첫 결과를 정확한 주소로 단정하지 않고 기존 정확도 규칙을 유지한다.
- 같은 실행 중에는 정규화된 위치 문자열을 키로 좌표 결과를 메모리 캐시한다.
- 탭이 보인 뒤 `map.relayout()`과 중심 재설정을 수행한다.
- SDK 또는 위치 검색 실패 시 Drawer 전체를 실패시키지 않고 위치 안내와 외부 Kakao 지도 검색 링크를 제공한다.

## 상태와 접근성

- 탭은 `role="tablist"`, `role="tab"`, `role="tabpanel"`, `aria-selected`를 사용한다.
- 좌우 또는 상하 방향키로 탭을 전환하고 활성 패널에 포커스를 이동한다.
- 지도는 보조 정보다. 위치명과 정확도 안내를 항상 텍스트로 제공한다.
- 기존 Escape 닫기, 포커스 트랩, 닫은 뒤 원래 `상세 보기` 버튼으로 포커스 복귀를 유지한다.
- 탭 전환 중 로딩, 키 미설정, 검색 결과 없음, SDK 오류 상태를 각각 표시한다.

## 변경 대상

| 파일 | 변경 내용 |
| --- | --- |
| `public/index.html` | Drawer 헤더·탭·패널을 담을 고정 구조 추가 |
| `public/app.css` | 640px Drawer, 좌측 탭, 지도 패널, 모바일 상단 탭 스타일 |
| `public/app.js` | 상세 렌더링 분리, 탭 전환, 지도 lazy-load 및 Drawer 생명주기 연동 |
| `public/announcement-map.js` | 위치 정확도 판정, 검색어·외부 링크 생성, Kakao SDK 로더와 지도 표시 |
| `src/server.mjs` | `GET /api/config`에서 `KAKAO_MAP_APP_KEY` 전달 |
| `test/announcement-map.test.mjs` | 정확도 판정, 검색어, 외부 지도 링크 검증 |

기존에 수정 중인 파일의 다른 변경은 보존한다.

## 완료 기준

1. 상세 Drawer가 목업과 같은 좌측 탭 구조로 열린다.
2. `상세 정보` 내용과 기존 하단 버튼이 그대로 동작한다.
3. `지도`를 처음 열 때만 SDK를 로드한다.
4. 정확한 주소, 역명, 지역만 있음, 위치 없음 네 상태가 구분된다.
5. API 키가 없어도 오류 없이 위치 안내와 외부 지도 링크가 표시된다.
6. 모바일에서는 탭이 상단으로 이동하고 360px에서 겹침이 없다.
7. Escape, 배경 클릭, 포커스 트랩과 원위치 포커스 복귀가 유지된다.
8. `npm test`가 통과하고 브라우저에서 Drawer 전환을 직접 검증한다.
