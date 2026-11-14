# 기관별 PDF 파서 구현 계획

> 프론트엔드 파일 경로는 현재 정적 화면 기준이다. React 전환 이후 PDF 검토 UI 구조는 [React 프론트엔드 전환 아키텍처](../ui/react-frontend-architecture.md)를 우선하며, 이 문서의 파서·근거·검증 규칙은 그대로 유지한다.

## 목표

관리자가 선택한 `siteCode`와 `documentType`만으로 파서를 결정한다. PDF 본문·파일명으로 기관이나 유형을 추측하지 않는다.

모든 파서는 기존 `DocumentParser.ParseResult` 계약을 반환한다. 따라서 DB 저장, 관리자 PDF 검토 화면, 확정 이력, 사용자 카드·상세 화면은 재사용한다.

## 공통 결과 계약

| 영역 | 필수 데이터 | 근거 |
| --- | --- | --- |
| `draft.sites` | 단지명, 주소, 시행사, 시공사, 주택형, 전용면적, 공급호수, 동·층·호, 보증금, 월세, 관리비, 전환 조건 | 각 값마다 `fieldPath`, PDF 페이지, 원문, 좌표, 추출 방식, 검토 상태 |
| `draft.events` | 공고일, 접수 기간, 당첨 발표, 계약, 입주 일정 | 동일 |
| `draft.eligibilityRules` | 연령, 무주택, 소득, 자산, 순위 등 | 동일 |

값이 없거나 단위·표 열·병합 셀 관계가 불명확하면 값을 만들지 않는다. 해당 근거를 `NEEDS_REVIEW(검토 필요)`로 남기고 관리자 수정 후에만 확정한다.

## 구현 단위

### 1. 파서 선택 기반

- `DocumentParserKey(siteCode, documentType)`와 `DocumentParserRegistry`를 추가한다.
- 기존 `SEOUL_YOUTH_SAFE / PRIVATE_RENTAL_NOTICE`는 기존 파서에 연결한다.
- 1차 추가 조합:
  - `LH / HAPPY_HOUSING_NOTICE`
  - `LH / NATIONAL_RENTAL_NOTICE`
- `DocumentImportService.create()`의 단일 기관·유형 차단 로직을 레지스트리 기반 지원 여부 검증으로 교체한다.
- 미지원 조합은 업로드 전에 명확히 실패시킨다. 자동 대체 파서는 사용하지 않는다.

### 2. 관리자 선택 UI

- 기관 선택: 서울 청년안심주택, LH, SH, 서울주거포털
- 기관별 지원 문서 유형만 표시한다.
- 서울주거포털은 목록/유입 경로로 쓰되, 원문 PDF 구조가 SH·LH와 동일하면 해당 기관 파서를 재사용하지 않고 `siteCode`+`documentType` 조합별 명시적 매핑을 둔다.
- 지원사업 문서는 주택 공고와 다른 유형으로 분리하며, 별도 PDF 구조가 확인되기 전에는 선택지에 노출하지 않는다.

### 3. LH 1차 파서

- `LhHappyHousingPdfParser`, `LhNationalRentalPdfParser`를 별도 구현한다.
- 우선 추출: 단지·주소, 주택형·면적·공급호수, 보증금·월세, 접수·당첨·계약 일정, 연령·무주택·소득·자산·순위.
- 추출한 값에는 PDF 텍스트 좌표와 원문을 남긴다.
- 공급호수와 개별 호실 개수, 금액 단위, 표 열 위치가 불확실하면 해당 값은 비우고 `NEEDS_REVIEW` 근거·경고를 남긴다.

### 4. 확정 안전장치

- 파서 결과는 항상 `DRAFT`다. 파서가 `COMMITTED` 상태를 만들 수 없다.
- 현재 관리자 검증·확정 동작만 정규화 상세 DB와 사용자 화면에 반영한다.
- 필수 필드의 금액 단위 불명·텍스트 레이어 없음·구조 미지원은 검증 오류 또는 검토 경고로 표시한다.
- 확정 이력의 PDF hash, 전후 JSON, 변경 차이 기능은 기존 구조를 그대로 사용한다.

## 후속 우선순위

1. SH 공공임대: 샘플 5건을 표 구조별로 분류한 뒤 `SH / PUBLIC_RENTAL_NOTICE` 구현
2. 서울주거포털: 주택 공고와 지원사업을 분리하고, 고유 PDF 구조가 있는 문서만 별도 파서 구현
3. LH·SH 추가 유형: 실제 관리자가 `검토 필요`로 가장 많이 수정한 유형 순서로 확대

## 완료 기준

- LH 행복주택·국민임대 실제 샘플을 합쳐 5건 이상 회귀 테스트
- 핵심 정보의 값마다 페이지·원문·좌표 근거 존재
- 불확실한 값은 추정하지 않고 `검토 필요` 처리
- 관리자 화면에서 수정·검증·확정 가능
- 확정 후 사용자 카드·상세 API가 정규화 DB의 확정 값만 반환

## 주요 변경 파일

| 파일 | 변경 |
| --- | --- |
| `spring-app/.../DocumentParser.java` | 파서 키/공통 계약 보강 |
| `spring-app/.../DocumentImportService.java` | 레지스트리로 파서 선택 |
| `spring-app/.../LhHappyHousingPdfParser.java` | 신규 |
| `spring-app/.../LhNationalRentalPdfParser.java` | 신규 |
| `public/admin-import.html`, `public/admin-import.js` | 기관·문서 유형 선택지 연동 |
| `spring-app/.../DocumentImportTests.java` | 실제 샘플 기반 회귀 테스트 |
