# 공고 DB 및 다중 공급 주택 구현 설계

## 결정 사항

- 기존 Spring SQLite(`data/housing-monitor.db`)와 `JdbcTemplate`을 확장한다.
- Spring `HousingMonitorService`를 운영 수집기의 기준으로 삼는다.
- 공고·공급 주택의 기준 저장소는 DB다. JSON은 전환 기간 동안 DB에서 생성하는 호환 출력물로만 유지한다.
- 기존 공고 `id`를 유지해 관심 저장, 모바일 알림, 알림 이력을 깨뜨리지 않는다.
- 상세 원문에서 확인되지 않은 주소·가격·면적은 추정해 저장하지 않는다.
- 새 ORM과 별도 DB 제품은 추가하지 않는다.

## 정규화 모델

```text
announcement_sources 1 ── N announcements N ── 1 providers
                                    │
                                    ├── N announcement_documents
                                    │
                                    └── N announcement_sites N ── 1 housing_sites ── 1 locations
                                                │
                                                └── N supply_units
                                                          │
                                                          └── N rental_options

collection_runs 1 ── N collection_source_runs
```

### `announcement_sources`

수집 기관 식별 정보다. 요청 URL, 페이지 제한 등 실행 설정은 당분간 `sources.json`에 두되 DB의 기관 행과 `id`로 연결한다.

- `id TEXT PRIMARY KEY`
- `name TEXT NOT NULL`
- `kind TEXT NOT NULL`
- `active INTEGER NOT NULL`

### `providers`

동일 공급기관 문자열의 반복을 줄인다.

- `id TEXT PRIMARY KEY`
- `normalized_name TEXT NOT NULL UNIQUE`
- `display_name TEXT NOT NULL`

### `announcements`

공고문 단위 정보다.

- `id TEXT PRIMARY KEY`: 기존 해시 ID
- `source_id TEXT NOT NULL REFERENCES announcement_sources(id)`
- `source_announcement_key TEXT NOT NULL`
- `provider_id TEXT REFERENCES providers(id)`
- `title TEXT NOT NULL`
- `housing_type TEXT`
- `announcement_date TEXT`
- `application_start TEXT`
- `application_end TEXT`
- `status TEXT`
- `official_url TEXT NOT NULL`
- `detail_status TEXT NOT NULL`: 마지막으로 사용할 수 있는 상세 결과. `NOT_COLLECTED`, `PARSED`, `PARTIAL`, `NEEDS_REVIEW`, `NOT_APPLICABLE`
- `detail_attempt_status TEXT`: 최근 시도 결과. `SUCCEEDED`, `FAILED`, `SKIPPED`
- `detail_last_attempt_at`, `detail_next_retry_at TEXT`
- `detail_attempt_count INTEGER NOT NULL DEFAULT 0`
- `detail_error TEXT`
- `detail_parser_version`, `detail_source_hash TEXT`
- `content_hash TEXT NOT NULL`
- `first_seen_at TEXT NOT NULL`
- `last_seen_at TEXT NOT NULL`
- `last_changed_at TEXT NOT NULL`
- `active INTEGER NOT NULL`
- `raw_summary_json TEXT`: 아직 정규화하지 않은 출처별 목록 필드 보존

제약:

```sql
UNIQUE(source_id, source_announcement_key)
```

API에서는 `announcementDate`를 표준 이름으로 사용하고 기존 `announcedAt` 입력은 이 필드로 변환한다.

### `locations`

주소와 좌표를 담당한다. 행정구역 문자열을 별도 코드 테이블로 과도하게 분리하지 않는다.

- `id TEXT PRIMARY KEY`
- `canonical_key TEXT NOT NULL UNIQUE`
- `region1`, `region2`, `region3 TEXT`
- `address TEXT`
- `latitude`, `longitude REAL`
- `accuracy TEXT NOT NULL`: `EXACT`, `LANDMARK`, `DISTRICT`, `UNKNOWN`
- `geocoded_at TEXT`

정확한 도로명·지번 주소 또는 출처가 제공한 위치 ID가 있을 때만 공고 간 동일 위치로 재사용한다. 역명·구 단위 정보는 서로 다른 건물로 잘못 합치지 않도록 공고 ID가 포함된 `canonical_key`를 사용한다.

### `housing_sites`

물리적 단지·건물이다.

- `id TEXT PRIMARY KEY`
- `canonical_key TEXT NOT NULL UNIQUE`
- `name TEXT`
- `location_id TEXT REFERENCES locations(id)`

### `announcement_sites`

공고와 물리적 건물의 연결이다. 입주 예정 문구처럼 공고마다 달라지는 값은 여기에 둔다.

- `id TEXT PRIMARY KEY`
- `announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE`
- `site_id TEXT NOT NULL REFERENCES housing_sites(id)`
- `source_site_key TEXT NOT NULL`
- `display_order INTEGER NOT NULL`
- `move_in_text TEXT`
- `eligibility_text TEXT`

제약:

```sql
UNIQUE(announcement_id, source_site_key)
```

### `supply_units`

해당 공고에서 공급하는 주택형·공급 구분이다.

- `id TEXT PRIMARY KEY`
- `announcement_site_id TEXT NOT NULL REFERENCES announcement_sites(id) ON DELETE CASCADE`
- `source_unit_key TEXT NOT NULL`
- `unit_type TEXT`
- `exclusive_area_m2 REAL`
- `supply_count INTEGER`
- `category_text TEXT`
- `floor_text TEXT`
- `room_text TEXT`
- `display_order INTEGER NOT NULL`

제약:

```sql
UNIQUE(announcement_site_id, source_unit_key)
```

### `rental_options`

같은 주택형의 보증금·월세 전환 조합을 각각 보존한다.

- `id TEXT PRIMARY KEY`
- `supply_unit_id TEXT NOT NULL REFERENCES supply_units(id) ON DELETE CASCADE`
- `option_key TEXT NOT NULL`
- `condition_text TEXT`
- `deposit_won INTEGER`
- `monthly_rent_won INTEGER`
- `management_fee_text TEXT`
- `display_order INTEGER NOT NULL`

제약:

```sql
UNIQUE(supply_unit_id, option_key)
```

### `announcement_documents`

상세 페이지와 첨부파일의 수집·파싱 근거다. 파일 본문을 DB BLOB으로 중복 저장하지 않는다.

- `id TEXT PRIMARY KEY`
- `announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE`
- `document_url TEXT NOT NULL`
- `document_type TEXT NOT NULL`
- `content_hash TEXT`
- `local_path TEXT`
- `retrieved_at TEXT`
- `parser_version TEXT`
- `parse_status TEXT NOT NULL`
- `parse_error TEXT`

### 수집 실행 테이블

`collection_runs`는 전체 실행 상태, `collection_source_runs`는 기관별 성공·실패와 건수를 저장한다. 기관 실패 시 해당 기관의 기존 공고를 비활성화하지 않는다.

## 저장 규칙

1. 네트워크 요청과 파싱은 DB 트랜잭션 밖에서 실행한다.
2. 기관별 수집 성공 후 짧은 트랜잭션으로 목록을 upsert한다.
3. 신규 공고 또는 `content_hash` 변경 공고만 상세 수집 대상으로 삼는다.
4. 상세 파싱에 성공한 공고는 해당 공고의 `announcement_sites` 이하 행을 트랜잭션에서 교체한다.
5. `PARTIAL` 결과도 확인된 행만 저장하며 누락값을 추정하지 않는다.
6. 전체 기관 수집 성공 시 이번 실행에서 보이지 않은 해당 기관 공고만 `active=0`으로 바꾼다.
7. 최초 이관 실행은 알림 기준선이며 신규 알림을 만들지 않는다.
8. 신규 알림은 DB commit 이후 생성한다.

## 실제 상세 수집

목록 수집이 끝나면 `HousingDetailCollector`가 실행된다. 한 번에 처리할 수는 `HOUSING_DETAIL_MAX_PER_RUN`으로 제한하며 기본값은 10건이다.

1. 활성 LH 공고 중 미수집, 목록 변경, 파서 버전 변경, 재시도 시각 도달 공고를 선택한다.
2. 공식 상세 HTML을 순차적으로 요청하고 요청 사이에 300ms 간격을 둔다.
3. `LhDetailParser`가 `공급정보` 영역을 단지별로 나눈다.
4. 단지의 명칭·주소·좌표·입주예정월과 주택형의 전용면적·공급호수·보증금·월세를 계층 구조로 변환한다.
5. 확인된 필드가 모두 있으면 `PARSED`, 일부가 빠지면 `PARTIAL`로 저장한다.
6. 원문 HTML은 `data/documents/{announcementId}/{contentHash}.html`에 저장하고 DB에는 경로와 해시만 기록한다.
7. 파싱과 저장이 모두 성공했을 때만 기존 상세 계층을 교체한다. 실패하면 마지막 `PARSED`/`PARTIAL` 데이터를 유지한다.

429, 5xx, 연결 실패, timeout은 10분 → 1시간 → 6시간 → 24시간 간격으로 재시도한다. HTML 구조를 해석하지 못한 경우는 자동 반복하지 않고 `NEEDS_REVIEW`로 남기며, 목록 내용이나 파서 버전이 바뀌면 다시 시도한다. 당첨 결과처럼 모집 상세가 아닌 글은 `NOT_APPLICABLE`로 분류한다.

현재 운영 파서는 실제 구조가 검증된 LH HTML 상세 페이지와 서울 청년안심주택 민간임대 PDF를 지원한다. SH·서울주거포털·다른 청년안심주택 PDF/HWP는 원문 fixture와 문서별 표 규칙을 확보한 뒤 기관별로 추가한다.

## 사용자 상세 화면 반환 규칙

사용자 API는 PDF 초안이나 원문 근거를 직접 반환하지 않는다. 확정 저장된 정규화 데이터만 공고 상세에 조합한다.

- `sites[]`: 단지명·주소·기관 역할·주택형·호실·임대조건
- `events[]`: 공고부터 입주까지의 원문 확인 일정
- `application`: `events[]`의 `APPLICATION` 일정만 별도로 투영해 카드·목록 마감일에 사용
- `eligibilityRules[]`: 신청 조건 탭에 표시할 연령·무주택·소득 등 기준

PDF에 값이 없거나 검토가 끝나지 않은 필드는 빈 값으로 두며, 목록 HTML 또는 다른 필드로 추정해 채우지 않는다.

## 안정적인 하위 키

- 출처가 건물·주택형 키를 제공하면 그대로 사용한다.
- 키가 없으면 정규화된 `건물명 + 정확한 주소`, `주택형 + 전용면적 + 공급구분`으로 해시한다.
- HTML 표의 행 번호나 화면 정렬 순서는 키로 사용하지 않는다.
- 주소가 역명·구 수준이면 공고 ID를 해시에 포함해 다른 공고의 건물과 합치지 않는다.

## 검색 규칙

지역·면적·보증금·월세 조건은 같은 `announcement_site → supply_unit → rental_option` 경로에서 모두 만족해야 한다. 서로 다른 주택의 보증금과 월세를 하나의 일치 조건으로 합치지 않는다.

검색 응답은 공고별로 다음 정보를 계산한다.

- 조건에 일치한 위치 목록
- 조건에 일치한 공급 주택 수
- 일치 주택의 보증금·월세·면적 범위
- 전체 위치 수와 대표 지역 요약

## API 호환과 전환

- `/api/overview` 응답 키는 우선 유지하되 DB에서 생성한다.
- `allAnnouncements.items`는 DB의 공고 요약을 반환한다.
- `announcementDetails.items[id]`는 기존 단일 상세 필드와 함께 `sites[]` 계층을 반환한다.
- 상세 조회용 `GET /api/announcements/{id}`를 추가해 공고·위치·주택형·임대 조건을 반환한다.
- 검색량이 커지면 `GET /api/announcements`에 서버 필터와 페이지 처리를 추가한다. 첫 DB 이관에서 기존 화면을 동시에 전면 교체하지 않는다.
- DB에서 기존 형식의 JSON을 export해 Node 정적 서버 호환을 유지한다. JSON을 별도로 직접 수정하지 않는다.

## 화면

- 목록 카드 하나는 공고 하나를 나타낸다.
- 지역은 `관악구 외 3곳`, 가격·면적은 확인된 공급 주택의 범위로 요약한다.
- 상세 Drawer 탭은 `공고 정보 / 공급 주택 / 지도`로 구성한다.
- 공급 주택 탭은 위치별로 묶고 주택형과 임대 조합을 하위에 표시한다.
- 지도는 모든 위치를 표시하고 정확한 주소는 마커, 역·지역은 범위로 표시한다.
- 검색 조건이 적용된 진입에서는 일치한 공급 주택을 우선 펼친다.

## 초기 이관

- `all-announcements.json`의 약 2,400건을 `announcements`로 가져온다.
- `announcement-details.json`의 5건은 각각 위치 1개, 주택형 1개, 임대 조건 N개로 변환한다.
- 기존 공고 ID를 그대로 사용한다.
- 이관 완료 후 DB 건수와 JSON 건수, 상세 5건의 비용 조합을 검증한다.
- DB가 비어 있을 때만 자동 이관한다.

## 구현 범위와 안전 기준

- 실제 원문에서 다중 위치를 확인할 수 있는 파서 결과는 그대로 여러 행으로 저장할 수 있어야 한다.
- 현재 저장소에 실제 상세 원문 fixture가 없는 기관은 값을 추정하는 범용 정규식 파서를 만들지 않는다. 해당 공고는 `NEEDS_REVIEW`로 둔다.
- 다중 위치·주택·임대 조건 fixture로 DB 저장, API, 검색 의미, UI 렌더링을 끝까지 검증한다.
- LH 운영 상세 파서는 실제 HTML 구조를 기준으로 동작한다. 다른 기관은 실제 HTML/PDF fixture를 확보한 뒤 기관별로 추가한다.

## 완료 기준

1. Spring 시작 시 정규화 테이블이 생성되고 기존 JSON이 한 번만 이관된다.
2. 수동·예약 수집이 목록 결과를 DB에 저장하며 실패 기관의 기존 행을 보존한다.
3. DB가 `/api/overview`, 모바일 공고 API, 알림의 기준 저장소가 된다.
4. DB에서 호환 JSON을 생성해 기존 Node 화면도 깨지지 않는다.
5. 한 공고에 위치 2개 이상, 주택형·임대 조건 여러 개를 저장하고 조회할 수 있다.
6. 검색 조건이 동일한 임대 조건 행에서 평가된다.
7. Drawer에서 위치별 공급 주택과 여러 지도 위치를 확인할 수 있다.
8. 기존 관심 저장 ID, 신규 알림 기준선, 지도 fallback이 유지된다.
9. 저장소·수집·API·프론트 테스트와 360px/데스크톱 브라우저 검증이 통과한다.
