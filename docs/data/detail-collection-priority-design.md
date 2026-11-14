# 상세 공고 수집 우선 개선 설계

> 프론트엔드 파일 경로는 현재 정적 화면 기준이다. React 전환 이후 UI 구현 구조는 [React 프론트엔드 전환 아키텍처](../ui/react-frontend-architecture.md)를 우선하며, 이 문서의 수집·데이터 규칙은 그대로 유지한다.

## 결론

다음 개발은 세 단계로 진행한다.

1. **1차 배포:** LH 상세 수집 정확도 수정, `PARTIAL` 정보 화면 표시, 수집 결과 확인
2. **2차 배포:** SH 첨부 PDF 상세 수집
3. **3차 배포:** 청년안심주택·서울 공공임대 상세 수집과 백필 운영 화면

현재 활성 공고 2,411건 중 SH가 1,735건, 청년안심주택이 542건, 서울 공공임대가 84건, LH가 50건이다. LH 수집기를 안정화한 뒤 SH를 지원해야 사용자에게 제공되는 상세 공고 비율이 실질적으로 높아진다.

## 1차 배포 범위

### 1. LH 목록·상세 파싱 정확도 수정

#### 공고명 정리

LH 목록의 공고명 안에 포함된 `1일전` 같은 상대 날짜 표시를 제거한다. 상대 날짜가 바뀌어도 `content_hash`가 변경되지 않아야 한다.

- 대상: `HousingMonitorService.parseLh()`
- 처리: 공고명 조각을 Jsoup으로 파싱한 후 `.day` 요소를 제거하고 텍스트만 저장
- 유지: `[정정공고]` 같은 공고명 자체의 접두사는 제거하지 않음

#### 공급호수 헤더 변형 지원

다음 헤더를 모두 같은 필드로 처리한다.

- `금회공급 세대수`
- `금회공급 세대수(예비자 포함)`

헤더 정규화 후 `금회공급세대수`로 시작하는 열을 찾는다. 다른 열은 기존처럼 정확히 일치시킨다.

- 대상: `LhDetailParser.column()`과 공급호수 열 선택
- 누락 시: `supplyCount=null`, 상세 상태는 `PARTIAL`
- 숫자 `0`: 유효한 값으로 저장

#### HTTP 재시도 분류

다음 경우만 자동 재시도한다.

| 오류 | 처리 |
| --- | --- |
| timeout, 연결 실패 | 재시도 |
| HTTP 429 | 재시도 |
| HTTP 500~599 | 재시도 |
| HTTP 400~499, 단 429 제외 | 자동 재시도하지 않고 `NEEDS_REVIEW` |
| HTML 파싱 실패 | 자동 재시도하지 않고 `NEEDS_REVIEW` |

`HttpStatusException`을 `IOException`보다 먼저 판별한다.

#### 상세 확인 시각과 문서 상태

`announcements`에 `detail_verified_at TEXT`를 추가한다.

- 상세 저장 성공 시에만 갱신
- `detail()`의 `verifiedAt`은 `last_changed_at` 대신 `detail_verified_at` 사용
- 상세 결과가 `PARTIAL`이면 `announcement_documents.parse_status`도 `PARTIAL`
- 실패 시 기존 `detail_verified_at`, `detail_status`, 상세 계층은 유지

### 2. `PARTIAL` 상세정보 표시

현재처럼 공고 전체를 `VERIFIED` 또는 미확인으로 나누지 않고 필드별로 표시한다.

#### 프론트 판정

`verifiedDetailFor()`를 다음 의미의 `usableDetailFor()`로 변경한다.

- `VERIFIED`: 모든 확인된 상세 필드 사용
- `PARTIAL`: 존재하는 필드만 사용
- `NEEDS_REVIEW` 또는 상세 없음: 목록 필드만 사용

API 응답 구조는 변경하지 않는다. `announcementDetails.items[id]`의 기존 `verificationStatus`를 그대로 사용한다.

#### 카드 표시 규칙

| 항목 | 상세 값 있음 | 상세 값 없음 |
| --- | --- | --- |
| 지역 | 상세 주소 또는 대표 지역 | 목록 지역 또는 `공고문 확인` |
| 보증금 | 확인된 최소~최대 금액 | `공고문 확인` |
| 월세 | 확인된 최소~최대 금액 | `공고문 확인` |
| 전용면적 | 확인된 최소~최대 면적 | `공고문 확인` |

- `PARTIAL`이라는 기술 용어를 카드 본문에 노출하지 않는다.
- 카드 하단에 필요한 경우에만 `일부 조건은 공식 공고문에서 확인하세요.`를 표시한다.
- 확인된 값을 `공고문 확인` 문구로 덮어쓰지 않는다.

#### Drawer 표시 규칙

- `공급 주택` 탭은 `PARTIAL`이어도 `sites[]`가 있으면 표시
- 주택형별 면적과 공급호수 표시
- 임대조건이 없는 주택형만 `임대 조건은 공고문에서 확인하세요.` 표시
- 지도는 확인된 주소·좌표가 있으면 `PARTIAL`이어도 사용
- 공식 공고문 버튼은 항상 유지

#### 홈 추천 공고

추천 후보 우선순위는 다음과 같다.

1. 관심 조건과 일치하고 `VERIFIED`
2. 관심 조건과 일치하며 지역·면적 중 하나 이상 확인된 `PARTIAL`
3. 신규 공고

`PARTIAL` 공고는 확인되지 않은 보증금·월세 조건을 만족한 것으로 간주하지 않는다.

### 3. 수집 결과 확인

`HousingMonitorService`가 반환하는 `detailCollection`을 `RefreshJob`에서도 보존한다.

```json
{
  "attempted": 10,
  "parsed": 2,
  "partial": 7,
  "failed": 1,
  "skipped": 0
}
```

- 목록 수집 성공과 상세 수집 성공을 별도로 표현
- 상세 일부 실패만으로 전체 목록 수집을 실패 처리하지 않음
- 일반 사용자 화면에는 이 통계를 표시하지 않음
- 현재 수집 상태 API에서 확인할 수 있게만 제공

초기 LH 백필은 별도 큐나 새 테이블을 만들지 않는다. 활성 LH 공고가 50건이므로 기존 후보 조회와 실행당 제한을 재사용한다. 운영 환경에서 `HOUSING_DETAIL_MAX_PER_RUN=20`으로 세 번 실행한 후 기본값 10으로 되돌린다.

## 파일별 변경 계획

| 파일 | 변경 내용 |
| --- | --- |
| `HousingMonitorService.java` | LH 공고명 상대 날짜 제거, 상세 결과 전달 유지 |
| `LhDetailParser.java` | 공급호수 헤더 변형 지원 |
| `HousingDetailCollector.java` | HTTP 상태별 재시도 분류 |
| `AnnouncementRepository.java` | `detail_verified_at` 마이그레이션, 문서 상태와 상세 상태 일치 |
| `RefreshJob.java` | `detailCollection` 필드 추가 |
| `MonitorRefreshCoordinator.java` | 상세 수집 결과를 작업 상태에 보존 |
| `public/app.js` | `PARTIAL` 상세 사용, 필드별 fallback 적용 |
| `public/app.css` | 부분 상세 안내 상태에 필요한 최소 스타일만 추가 |
| `HousingDetailCollectorTests.java` | 파서·재시도·기존 상세 보존 테스트 보강 |
| 프론트 테스트 | `PARTIAL` 지역·면적 표시와 비용 미확인 상태 검증 |

## 테스트 설계

### 백엔드

1. `금회공급 세대수(예비자 포함)`에서 공급호수를 저장한다.
2. 공급호수 `0`을 null로 바꾸지 않는다.
3. `.day` 값이 `1일전`에서 `2일전`으로 바뀌어도 공고 `content_hash`가 동일하다.
4. HTTP 404는 `detail_next_retry_at=null`이다.
5. HTTP 429와 500은 `detail_next_retry_at`이 설정된다.
6. `PARTIAL` 저장 시 문서 상태도 `PARTIAL`이다.
7. 재수집 실패 후 기존 단지·주택형·임대조건과 `detail_verified_at`이 유지된다.

### 프론트

1. 지역·면적만 있는 `PARTIAL` 공고가 카드와 Drawer에 표시된다.
2. 없는 보증금·월세만 `공고문 확인`으로 표시된다.
3. `PARTIAL` 공고를 금액 조건 일치 공고로 잘못 추천하지 않는다.
4. `sites[]`가 여러 개면 공급 주택 탭과 지도에 모두 표시된다.
5. 360px에서 카드의 2×2 핵심정보 영역이 겹치지 않는다.

## 1차 배포 완료 기준

- 최신 LH 표본 8건에서 단지·주소·면적·공급호수가 누락 없이 파싱된다.
- HTML에 실제 금액이 있는 공고는 `PARSED`, 공고문 확인이 필요한 공고는 `PARTIAL`로 저장된다.
- `PARTIAL` 공고도 확인된 지역·면적이 사용자 화면에 보인다.
- 목록의 상대 날짜 변화로 상세 재수집이 반복되지 않는다.
- 4xx 영구 오류가 반복 재시도되지 않는다.
- 전체 Spring 및 프론트 테스트가 통과한다.

## 2차 배포: SH PDF 상세 수집

1차 배포 완료 후 진행한다. SH가 활성 공고의 약 72%이므로 기관 확장 중 최우선이다.

- 상세 페이지에서 첨부파일 식별자와 다운로드 URL 확보
- PDF만 우선 지원하고 HWP/HWPX는 `NEEDS_REVIEW`
- PDFBox로 텍스트 추출
- SH 문서 유형별 fixture를 최소 3개 확보
- 단지·주소·주택형·면적·보증금·월세 표를 기관 전용 파서로 변환
- 문서 크기 제한, Content-Type 확인, 해시 중복 방지 적용
- 기존 `saveCollectedDetail()`과 정규화 테이블 재사용

새 메시지 큐, 별도 DB, 범용 OCR은 이 단계에서 추가하지 않는다. 텍스트 추출이 불가능한 스캔 PDF 비중이 확인될 때만 OCR을 검토한다.
