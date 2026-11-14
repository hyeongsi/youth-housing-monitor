# 현재 구현 기능과 코드 확인 가이드

> 현재 실행 화면은 `web/`의 React·TypeScript 소스를 Vite로 빌드한 정적 번들이다. Spring Boot는 `web/dist/`를 제공한다. 기존 `public/` 정적 화면은 제거됐고, 구조와 전환 원칙은 [React 프론트엔드 전환 아키텍처](ui/react-frontend-architecture.md)를 따른다.

## 1. 전체 구조

```text
공식 공고 사이트
  → HousingMonitorService: 기관별 목록 수집·공통 형식 변환
  → AnnouncementRepository: SQLite 저장·신규/변경 판정
  → HousingDetailCollector + 기관별 파서: 상세 페이지 수집
  → HousingDataController / MobileApiController: 웹·모바일 API
  → web/src/App.tsx / Android 앱: 사용자 화면
  → NotificationDispatchService: 신규 공고 조건 확인·FCM 전송
```

운영 기준 서버는 `spring-app`이다. Node 코드는 프론트 개발용 정적 서버와 보조 도구만 담당하며 공고를 수집하지 않는다.

### 처음 확인할 파일

| 확인 목적 | 먼저 볼 파일 |
| --- | --- |
| 전체 수집 흐름 | `spring-app/src/main/java/kr/seoulhousing/monitor/HousingMonitorService.java` |
| DB 구조와 저장 방식 | `spring-app/src/main/java/kr/seoulhousing/monitor/AnnouncementRepository.java` |
| LH 상세 수집 | `HousingDetailCollector.java`, `LhDetailParser.java` |
| 웹 API | `HousingDataController.java`, `MonitorRefreshController.java` |
| 사용자·관리자 라우팅 | `web/src/App.tsx` |
| 공통 UI·Toast | `web/src/components/ui.tsx`, `web/src/components/ui.module.css` |
| 지도 | `web/src/map.ts` |
| PDF 상세정보 가져오기 | `web/src/ReviewComplete.tsx`, `web/src/App.tsx` |
| PDF 추출·저장 API | `DocumentImportController.java`, `DocumentImportService.java`, `DocumentExtractionRepository.java` |
| 모바일·알림 | `MobileApiController.java`, `NotificationDispatchService.java`, `NotificationStore.java` |
| 주요 설정 | `data/sources.json`, `spring-app/src/main/resources/application.properties` |
| 데이터 모델 설계 | `docs/data/announcement-database-design.md` |
| 다음 개선 계획 | `docs/data/detail-collection-priority-design.md` |
| React 전환 설계 | `docs/ui/react-frontend-architecture.md` |

## 2. 애플리케이션 실행

### Spring 운영 서버

```powershell
cd spring-app
.\gradlew.bat bootRun --no-daemon
```

- 기본 주소: `http://127.0.0.1:4173`
- 시작점: `HousingMonitorApplication.main()`
- 정적 파일: `web/dist/` (`web/`에서 `npm run build` 후 생성)
- 데이터 위치: `data/`
- DB: `data/housing-monitor.db`

`application.properties`에서 서버 주소, SQLite 연결, 3시간 수집 주기, 상세 수집 실행당 제한을 설정한다.

### Node 개발용 서버

```powershell
npm run serve
```

`src/server.mjs`는 레거시 개발용 도구다. React 개발은 `web/`에서 `npm run dev`로 실행하며 `/api` 요청은 Spring 서버로 프록시한다. SQLite 공고 상세 API, 수동 수집, 모바일 API는 Spring 서버에서만 동작한다.

## 3. 공고 목록 수집

### 사용한 기능

- Java 21 `HttpClient`: 공식 사이트 요청
- Jackson: JSON 응답 파싱과 호환 JSON 출력
- 정규식 기반 표 파싱: LH·SH·서울주거포털 목록
- 출처 전용 JSON 처리: 청년안심주택
- `synchronized refresh()`: 한 프로세스 안에서 중복 전체 수집 방지

### 주요 코드

`HousingMonitorService.refresh()`가 전체 수집의 시작점이다.

1. `data/sources.json`을 읽는다.
2. 출처의 `kind`에 따라 수집 함수를 선택한다.
3. 기관별 성공 결과만 DB에 저장한다.
4. 실패 기관은 이전 정상 데이터를 유지한다.
5. DB에서 호환 JSON과 신규 공고 목록을 만든다.
6. 상세 수집기를 실행한다.
7. 수집 상태와 알림 입력 데이터를 기록한다.

기관별 주요 함수:

| 출처 | `kind` | 함수 |
| --- | --- | --- |
| LH | `lh-list` | `parseLh()` |
| SH | `sh-list` | `collectPaged()` → `parseSh()` |
| 서울주거포털 | `seoul-public-list` | `collectPaged()` → `parseSeoul()` |
| 청년안심주택 | `youth-safe-list` | `collectYouth()` |
| 상태 확인만 하는 페이지 | `snapshot` | HTTP 접근 여부만 확인 |

새 기관을 추가할 때는 먼저 `data/sources.json`에 출처를 정의하고, 기존 구조와 다르면 `HousingMonitorService`에 해당 기관 전용 파서를 추가한다. 목록에 없는 가격·면적을 추정해서 넣지 않는다.

## 4. 신규 공고와 변경 공고 판정

### 기준 ID

목록 파서는 `sourceId + 출처 공고 키`를 SHA-256으로 변환해 공고 `id`를 만든다. 같은 기관의 같은 공고는 제목이나 상태가 바뀌어도 같은 ID를 유지한다.

### 신규 판정

`AnnouncementRepository.saveSuccessfulSource()`가 수집 전 DB의 공고 ID와 이번 결과를 비교한다.

- 과거에 없던 ID: 신규
- 같은 ID지만 `content_hash`가 바뀜: 변경
- 이번 정상 수집에서 사라진 ID: `active=0`
- 기관 수집 실패: 해당 기관의 기존 `active` 상태를 변경하지 않음

첫 정상 수집은 기준선으로 취급해 기존 공고 전체를 신규 알림으로 보내지 않는다.

### 주로 볼 부분

- `saveSuccessfulSource()`: 신규·변경·비활성화 판정
- `upsertAnnouncement()`: 공고 hash 생성과 insert/update
- `collection_runs`, `collection_source_runs`: 실행 및 기관별 결과

## 5. SQLite 데이터 저장

`AnnouncementRepository.initialize()`가 필요한 테이블과 인덱스를 생성한다. 별도의 SQL 마이그레이션 도구 없이 기존 컬럼을 확인한 뒤 필요한 컬럼을 추가한다.

### 핵심 계층

```text
announcements
  └─ announcement_sites
       └─ housing_sites ─ locations
       └─ supply_units
            └─ rental_options
```

- `announcements`: 공고 목록 단위
- `housing_sites`: 실제 단지·건물
- `announcement_sites`: 공고와 단지 연결
- `supply_units`: 주택형·면적·공급호수
- `rental_options`: 같은 주택형의 보증금·월세 조합
- `announcement_documents`: 상세 HTML·첨부파일의 수집 근거
- `document_extractions`: PDF 분석 초안·검증 결과·확정 상태
- `extraction_evidence`: 입력 필드별 PDF 페이지·원문·좌표·추출 방식·검토 상태
- `supply_rooms`: 주택형에 연결된 동·층·호
- `site_organizations`, `announcement_events`, `eligibility_rules`: 기관 역할·일정·신청조건

`saveCollectedDetail()`은 하나의 트랜잭션 안에서 기존 상세 계층을 교체한다. 파싱이나 네트워크 요청이 실패하면 이 메서드를 호출하지 않으므로 마지막 정상 상세정보가 유지된다.

DB가 비어 있을 때는 `all-announcements.json`과 `announcement-details.json`을 한 번 가져온다. 이후 DB가 기준 저장소이고 두 JSON은 이전 실행 경로를 위한 호환 출력물이다.

## 6. 실제 상세 공고 수집

### 현재 지원 범위

현재 실제 상세 파서는 LH HTML 공고와 서울 청년안심주택 민간임대 PDF를 지원한다. SH·서울주거포털·다른 문서 유형의 첨부 PDF/HWP 파서는 아직 없다.

### 실행 흐름

`HousingDetailCollector.collect()`:

1. `AnnouncementRepository.detailCandidates()`에서 대상을 가져온다.
2. LH 공식 상세 페이지를 요청한다.
3. `LhDetailParser.parse()`로 단지와 주택형을 변환한다.
4. 원문 HTML을 `data/documents/{announcementId}/{contentHash}.html`에 저장한다.
5. 정규화한 계층과 시도 상태를 SQLite에 저장한다.

대상은 미수집 공고, 목록 내용 변경 공고, 파서 버전 변경 공고, 재시도 시각이 된 실패 공고다. 기본 실행당 제한은 10건이며 `HOUSING_DETAIL_MAX_PER_RUN`으로 변경한다.

### LH 파서가 읽는 값

- `div[id^=wrtancSbdTab]`: 단지 구분
- `소재지`: 주소와 행정구역
- 페이지 스크립트의 `lat_*`, `lng_*`: 공식 페이지 좌표
- `주택형 안내` 표: 주택형, 전용면적, 공급호수, 보증금, 월세
- `입주예정월`: 단지별 입주 문구

값이 모두 확인되면 `PARSED`, 일부만 확인되면 `PARTIAL`이다. 모집 상세가 아닌 당첨 결과 게시물은 `NOT_APPLICABLE`로 제외한다.

### 실패 처리

- 상세 실패는 목록 수집 성공을 취소하지 않음
- 기존 `PARSED`/`PARTIAL` 상세정보 보존
- timeout·연결 실패·일시적 HTTP 오류는 재시도 시각 기록
- 파싱할 수 없는 구조는 `NEEDS_REVIEW`

상세 수집을 수정할 때는 `HousingDetailCollectorTests`에서 다중 단지, 임대조건 연결, 실패 후 기존 데이터 보존을 먼저 확인한다.

### 서울 청년안심주택 PDF 상세 입력

목록 수집과 별개로 관리자가 `/admin-import.html`에서 기존 공고와 PDF를 연결해 상세 데이터를 검토·저장한다.

1. 사이트·문서 유형·기존 공고를 선택하고 PDF를 업로드한다.
2. `PdfTextLayoutExtractor`가 텍스트와 좌표를 읽고, `SeoulYouthSafePrivatePdfParser`가 주택개요·공급현황·일정·신청조건 섹션을 분석한다.
3. 자동 추출값은 즉시 운영 데이터에 반영하지 않고 `document_extractions` 초안으로 저장한다.
4. 관리자는 PDF 원문·추출 데이터·근거를 비교하고 값을 수정한 뒤 초안 저장, 검증, 확정 저장을 차례로 실행한다.
5. 확정 저장은 하나의 트랜잭션으로 기존 공고의 정규화 상세 계층에 반영한다. `announcement_sites`·`locations`·`supply_units`·`rental_options`에는 위치·주택형·임대조건을, `site_organizations`·`supply_rooms`·`announcement_events`·`eligibility_rules`에는 기관·호실·일정·신청조건을 저장한다. 실패하면 운영 데이터는 바뀌지 않고 초안은 유지된다.
6. 사용자 공고 상세 API는 위 데이터를 한 번에 반환한다. 상세 화면은 공고 정보에서 전체 일정을, 공급 주택에서 기관·호실·임대조건을, 신청 조건 탭에서 기준을 표시한다. PDF에 없는 값은 추정하지 않는다.
7. 확정 저장과 복원은 `announcement_detail_revisions`에 이전·이후 스냅샷, 작업 유형, 시각, PDF hash를 함께 남긴다. 관리자는 PDF 검토 화면의 `상세정보 변경 이력`에서 이전 확정본으로 복원할 수 있으며, 복원 결과도 별도 이력으로 보존된다.

관리자 검토 화면은 다음 구조다.

- 업로드 단계: 공고 검색·연결, PDF 파일 선택, 분석 실행을 분리해 표시
- 요약: 단지·주택, 임대조건, 일정, 신청조건의 추출 여부를 먼저 표시
- 데스크톱: 왼쪽 PDF 원문, 가운데 편집 패널, 오른쪽 선택 근거를 동시에 표시
- 모바일: 한 열로 전환하고 PDF·근거 고정을 해제
- 근거 상태·기관 역할·일정·신청조건·추출 방식은 한글 라벨로 표시하지만 API·DB에는 영문 코드값을 유지
- 변경 이력: 확정·복원 시점별 데이터 건수와 PDF hash를 보여 주고, 선택한 확정본으로 복원
- 항목별 비교: 이력의 `변경 내용 보기`에서 보증금·월세·전용면적·주소·기관·호실·일정·신청조건의 이전값과 이후값을 한글 라벨로 표시

자세한 입력 규칙과 DB·API 계약은 `docs/data/pdf-import-review-screen-design.md`를 확인한다.

## 7. 웹 API

### 사용자 데이터 API

`HousingDataController`:

| API | 역할 |
| --- | --- |
| `GET /api/overview` | 프로필, 제도, 수집 상태, 홈 미리보기 공고·필터 facet·공고 수 요약을 반환 |
| `GET /api/announcements` | 서버 필터·정렬·페이지네이션이 적용된 공고 목록 반환 |
| `GET /api/announcements/{id}` | 공고 한 건과 단지·주택형 상세 반환 |
| `GET /api/config` | 카카오 지도 앱 키 전달 |
| `PUT /api/profile` | 허용된 로컬 프로필 필드만 저장 |

`/api/overview`는 첫 화면에 필요한 최대 12건의 홈 미리보기와 `catalogFacets`, `catalogSummary`만 반환한다. 전체 공고 배열과 전체 상세 맵은 포함하지 않는다.

`GET /api/announcements`는 `page`(1부터), `size`(최대 50), 지역·유형·상태·출처·검색어·보증금/월세 상한·전용면적 범위·정렬·마감 포함 여부를 SQL에서 처리한다. `includeUnknown=false`는 위치·임대조건·면적이 모두 확인된 공고만 반환한다. 관심 조건은 `interestOnly`와 `interestRegion`·금액·면적 조건으로, 저장 공고는 반복 `savedId` 파라미터로 같은 서버 검색 조건에 포함한다. 응답의 `totalCount`와 `hasNext`는 이 조건 전체 기준이다.

### 수집 API

`MonitorRefreshController`:

| API | 역할 |
| --- | --- |
| `POST /api/monitor/refresh` | 백그라운드 수집 시작 |
| `GET /api/monitor/refresh` | 현재 수집 작업 상태 확인 |

`MonitorRefreshCoordinator`는 단일 스레드 실행기를 사용한다. 이미 수집 중이면 중복 실행하지 않고, 수동 수집 완료 후 10분 동안 재실행을 제한한다.

`ScheduledMonitorRefresh`는 기본 3시간마다 같은 coordinator를 호출하므로 수동 수집과 예약 수집이 겹치지 않는다.

### PDF 상세 입력 API

| API | 역할 |
| --- | --- |
| `GET /api/admin/announcements?query=` | 연결할 기존 공고 검색 |
| `POST /api/admin/document-imports` | PDF 업로드·분석 초안 생성 |
| `GET`/`PATCH /api/admin/document-imports/{id}` | 초안 조회·수정 |
| `POST /api/admin/document-imports/{id}/validate` | 저장 전 검증 |
| `POST /api/admin/document-imports/{id}/commit` | 검토값 확정 저장 |
| `GET /api/admin/document-imports/{id}/document` | 검토용 원문 PDF 제공 |

## 8. 사용자 웹 화면

> 아래의 업무 동작·표시 규칙은 유지한다. `load()`, `setActiveView()` 등 기존 정적 JavaScript 함수명은 역사적 구현 기록이며, 현재 React 컴포넌트·상태·경로 기준은 `docs/ui/react-frontend-architecture.md`와 `web/src/`를 따른다.

### 화면 구조

React Router는 다음 경로 화면을 가진다.

- `/`: 추천 공고와 최근 공고
- `/announcements`: 전체 공고 검색
- `/preferences`: 관심 조건과 추가 프로필 메모
- `/programs`: 제도 안내

`web/src/App.tsx`의 React Router가 경로에 따라 한 화면을 표시한다. 뒤로 가기·앞으로 가기·새로고침에서도 같은 화면을 유지한다. 이전 hash 주소는 앱 시작 시 대응 경로로 바뀐다.

### 데이터 로딩

`load()`가 `/api/overview`를 호출해 전역 `state`를 구성한다. 최초 로딩에는 Skeleton, 실패 시 재시도 카드, 완료 후 각 화면 렌더링을 사용한다.

### 홈

- `recommendedAnnouncements()`: 관심 조건과 비교한 추천 후보
- `recentAnnouncements()`: 최근 공고
- `renderHome()`: 화면당 제한된 카드와 전체 공고 이동 버튼

추천은 실제 신청 자격 판정이 아니라 사용자가 저장한 지역·가격·면적 조건과 공개 공고 정보의 비교다.

### 공고 검색

검색 조건은 두 상태로 나뉜다.

- `catalogState.draftFilters`: 입력 중인 값
- `catalogState.appliedFilters`: 검색 버튼을 눌러 적용한 값

`syncDraftFilters()`는 입력만 기록하고 결과를 바꾸지 않는다. `applyAnnouncementFilters()`가 검색 버튼 클릭 시 적용 상태를 갱신한다. 실제 필터링은 `filteredAnnouncements()`에서 수행한다.

검색 결과는 한 번에 10건을 표시한다. `공고 더 보기`는 새 API 요청이 아니라 이미 받은 목록에서 다음 10건을 이어 붙인다.

### 관심 조건

- `formatNumericInput()`: 숫자가 아닌 문자를 제거하고 3자리 쉼표 표시
- `setAreaValidation()`: 최소 면적이 최대 면적보다 큰지 검사
- `saveInterestPreferences()`: 브라우저 `localStorage`에 저장
- `classifyInterest()`: 지역·면적·가격 조건 비교

관심 조건은 브라우저별 설정이다. 서버의 모바일 알림 조건이나 추가 프로필 메모와는 별개다.

### 관심 저장

`toggleSavedAnnouncement()`가 관심 공고 ID를 `localStorage`에 저장한다. 서버 계정이나 DB에 동기화하지 않는다.

### 카드와 상세 Drawer

- `announcementResultCard()`: 공고 카드
- `openAnnouncementDrawer()`: 상세 Drawer
- `renderSupplySites()`: 단지별 주택형과 임대조건
- `activateDrawerTab()`: 공고 정보·공급 주택·지도 탭 전환
- `trapDrawerFocus()`: 키보드 포커스를 Drawer 안에 유지
- Escape: Drawer 닫기

Drawer가 닫힐 때는 열기 버튼으로 포커스를 되돌린다. 모바일에서는 CSS로 Drawer와 탭 배치를 변경한다.

## 9. 지도

`web/src/map.ts`가 위치 정보를 화면과 지도에 맞는 형태로 바꾼다.

### 위치 우선순위

1. 상세 `sites[].location`의 좌표
2. 상세 주소
3. 목록 지역
4. 공급기관·공고명 기반 검색어

`drawMap()`은 `/api/config`에서 `KAKAO_MAP_APP_KEY`를 읽는다.

- 좌표가 있으면 바로 마커 표시
- 주소만 있으면 카카오 장소 검색으로 좌표 확인
- 앱 키가 없거나 검색 실패 시 공고 위치 텍스트와 카카오맵 외부 링크 제공
- 여러 단지이면 모든 확인된 위치를 지도에 표시

지도는 Drawer의 지도 탭을 처음 열 때만 로드하며, Drawer를 닫거나 다른 공고로 바꾸면 이전 요청을 `AbortController`로 중단한다.

## 10. 관리자 화면과 수동 수집

`/admin`은 일반 사용자 기능과 분리된 운영 화면이다.

`web/src/App.tsx`의 `AdminDashboard`:

- `/api/overview`에서 최근 성공 시각, 공고 수, 신규 수, 기관별 상태 확인
- `지금 공고 수집` 클릭 시 `POST /api/monitor/refresh`
- 2초 간격으로 `GET /api/monitor/refresh` polling
- 버튼 비활성화와 회전 애니메이션으로 중복 클릭 방지
- 완료·일부 실패·실패 토스트 표시
- 실패 기관은 기존 데이터 유지 문구 표시

관리자 페이지는 현재 별도 로그인 없이 로컬 서버에서 사용한다. 외부 공개 전에는 인증과 접근 제어가 필요하다.

`PDF 상세정보 가져오기` 링크는 관리자 수집 화면 상단에 있다. 업로드 화면의 `분석 시작`은 실제 비동기 요청 상태에 맞춰 비활성화되며, 중복 클릭을 막는다.

## 11. 신규 공고 알림과 Android 앱

### 서버 알림 흐름

```text
refresh().newItems
  → NotificationDispatchService.dispatch()
  → 알림 조건 matches()
  → NotificationStore.reserveDelivery()
  → PushSender.send()
  → 전송 결과와 모바일 알림함 저장
```

`NotificationDispatchService.matches()`는 지역, 주택 유형, 상태, 제목 키워드를 비교한다. 빈 조건은 제한하지 않는다.

`PushSender` 구현:

- `FirebasePushSender`: `FCM_SERVICE_ACCOUNT_PATH`가 있을 때 실제 FCM 전송
- `LoggingPushSender`: Firebase 설정이 없을 때 로그만 남기는 개발용 구현

`NotificationStore`는 기기, 페어링 코드, 조건, 공고별 이벤트, 기기별 전송 결과, 앱 알림함을 같은 SQLite DB에 저장한다. `notification_events.announcement_id`와 전송 테이블의 복합 PK로 같은 공고의 중복 알림을 방지한다.

현재 실패 전송을 자동 재시도하지 않는다. 실패 행은 기록되지만 같은 공고·기기의 `reserveDelivery()`가 다시 생성되지 않는 구조다.

### 모바일 API 인증

1. 서버가 10분짜리 6자리 페어링 코드를 발급한다.
2. 앱이 코드, 기기명, FCM 토큰을 `/api/mobile/pair`로 보낸다.
3. 서버가 `deviceId`와 `accessToken`을 발급한다.
4. 이후 앱은 `X-Device-Id`, `Authorization: Bearer ...`로 요청한다.

주요 Android 파일:

| 기능 | 파일 |
| --- | --- |
| 페어링·메인 메뉴 | `android-app/.../MainActivity.java` |
| 인증 정보 저장 | `Session.java` |
| API 호출 | `ApiClient.java` |
| FCM 수신·알림 표시 | `HousingMessagingService.java` |
| 알림 조건 | `ConditionsActivity.java` |
| 공고 목록·상세 | `AnnouncementListActivity.java`, `AnnouncementDetailActivity.java` |
| 알림함 | `InboxActivity.java` |

## 12. JSON 파일의 역할

| 파일 | 역할 |
| --- | --- |
| `sources.json` | 수집할 공식 출처 설정 |
| `all-announcements.json` | DB 목록의 호환 출력 |
| `announcement-details.json` | DB 상세 계층의 호환 출력 |
| `monitor-state.json` | 최근 전체·기관별 수집 상태 |
| `auto-discovered.json` | 최근 신규 공고 |
| `notifications.json` | 신규 알림 후보와 출처 실패 |
| `profile.json` | 추가 프로필 메모 |
| `programs.json` | 제도 안내 콘텐츠 |

`all-announcements.json`과 `announcement-details.json`을 직접 수정하면 다음 DB export에서 덮어써질 수 있다. 운영 공고 데이터는 `AnnouncementRepository`를 통해 변경한다.

## 13. 테스트와 문제 확인 순서

### 실행

```powershell
cd spring-app
.\gradlew.bat test --no-daemon --console=plain

cd ..
npm test
```

### 주요 테스트

| 테스트 | 확인 내용 |
| --- | --- |
| `HousingMonitorApplicationTests` | 기관별 목록 파싱, 기존 JSON 동작 |
| `AnnouncementRepositoryTests` | DB 이관, 다중 단지 저장, 검색 의미, 실패 데이터 보존 |
| `HousingDetailCollectorTests` | LH 상세 수집, 원문 저장, 실패 후 상세 보존 |
| `MonitorRefreshCoordinatorTests` | 수동 수집 중복·상태·cooldown |
| `NotificationDispatchServiceTests` | 알림 조건·중복 방지·전송 결과 |
| `catalog-pagination-contract.test.mjs` | React 공고 검색의 서버 페이지 API·URL 적용 조건 |
| `react-ui-contract.test.mjs` | React PDF 등록·검토 API 계약과 지도 fallback 구성 |
| `status-contract.test.mjs` | 상태 JSON 계약 |
| `DocumentImportTests` | PDF 추출·근거·검증·확정 저장·롤백·HTML 값 불일치 경고 |

### 증상별 확인 위치

| 증상 | 확인 순서 |
| --- | --- |
| 공고 수가 갑자기 줄어듦 | `monitor-state.json` → 기관별 실패 → `saveSuccessfulSource()` |
| 신규 알림이 안 옴 | `newItems` → `matches()` → `notification_events` → `notification_deliveries` |
| 카드에 가격·면적이 안 보임 | `detail_status` → `announcement-details.json` → `verifiedDetailFor()` |
| 상세 단지가 비어 있음 | `announcement_documents` → `detail_error` → `LhDetailParser` 또는 `SeoulYouthSafePrivatePdfParser` |
| PDF 필드가 원문과 맞지 않음 | `extraction_evidence` → 업로드 화면의 근거 버튼 → 원문 PDF 페이지·검토 상태 |
| 검색 결과가 입력 즉시 바뀜 | `catalogState.draftFilters`와 `catalogState.appliedFilters` 연결 확인 |
| 지도가 안 보임 | `/api/config` → `KAKAO_MAP_APP_KEY` → 주소·좌표 → 외부 링크 fallback |
| 수집 버튼이 계속 로딩 | `GET /api/monitor/refresh` → coordinator 상태 → 기관 timeout |

## 14. 현재 한계와 다음 작업

- 서울 청년안심주택 민간임대 외 기관의 PDF/HWP 상세 파서는 아직 없다.
- `PARTIAL` 상세는 현재 사용자 화면에서 충분히 활용되지 않는다.
- 일부 LH 헤더 변형과 상대 날짜 공고명 처리가 보완 대상이다.
- 상세 수집 결과는 coordinator 작업 상태에 아직 포함되지 않는다.
- FCM 실패 자동 재전송은 제외되어 있다.
- Node 개발 서버는 Spring의 전체 API 기능을 대체하지 않는다.

구체적인 우선순위와 완료 기준은 `docs/data/detail-collection-priority-design.md`를 따른다.
