# React 프론트엔드 전환 아키텍처

## 문서 상태

- 상태: 구현 완료
- 기준일: 2026-11-13
- 범위: 사용자 화면, 관리자 화면, PDF 검토 화면
- 유지 대상: Spring Boot API, SQLite, 수집기, PDF 파서, 알림 로직

이 문서는 현재 프론트엔드 기준이다. React 소스는 `web/`에 있고 Vite 빌드 결과는 `web/dist/`에 생성된다. Spring Boot와 Docker는 이 번들을 제공한다. 기존 문서의 업무 규칙·접근성·API 계약은 유지하되, 화면 파일과 DOM 직접 조작 방식은 이 문서를 우선한다.

## 1. 목표와 제외 범위

### 목표

- 헤더, 메뉴, 버튼, 폼, 상태 표현을 화면별 복제 없이 통일한다.
- 홈과 공고 찾기의 공고 카드·상세 Drawer를 같은 컴포넌트로 사용한다.
- 사용자와 관리자 화면을 한 앱 안에서 경로·레이아웃으로 구분한다.
- `querySelector`, `innerHTML`, 수동 이벤트 등록 대신 타입이 있는 props와 상태로 렌더링한다.
- 기존 URL 직접 접속, 키보드 접근성, 모바일 UI, Spring API 계약을 보존한다.

### 제외 범위

- Spring API와 SQLite 스키마 변경
- 수집기·PDF 파서·FCM 로직 재작성
- CSS 프레임워크나 별도 전역 상태 라이브러리 도입

## 2. 현재 구조 점검

| 항목 | 현재 확인 결과 | 전환 방향 |
| --- | --- | --- |
| 앱 셸 | 4개 HTML에서 헤더·브랜드·본문 구조 반복 | `PublicLayout`, `AdminLayout` 통합 |
| 버튼 | 기본, `.official`, `.secondary`, compact, danger가 분산 | `Button`, `LinkButton` variant 통일 |
| 제목·섹션 | `.view-heading`, `.section-heading`이 여러 CSS에 중복 | `PageHeader`, `SectionHeader`와 CSS Module |
| 폼 | 숫자 단위 입력, 도움말, 오류, 토글 구조 반복 | `FormField`, `NumericField`, `CheckboxField` |
| 피드백 | Toast가 사용자·관리자·PDF 화면별로 각각 구현 | `ToastProvider` 하나로 통합 |
| 공고 기능 | 카드·Drawer·지도 렌더링이 `app.js`에 집중 | `features/announcements`로 분리 |
| PDF 검토 | 업로드·PDF·편집·근거·이력이 대형 스크립트에 집중 | `features/document-import`로 분리 |

기존 정적 코드는 React 전환 뒤 제거했다. 이전 주소는 Spring의 redirect와 앱 시작 시 hash 변환으로 호환한다.

## 3. 기술 결정

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| UI | React + TypeScript | 화면 상태와 props를 명확하게 관리 |
| 개발·빌드 | Vite | Spring과 독립된 개발 서버와 정적 빌드 |
| 라우팅 | React Router | 사용자·관리자 경로, 직접 URL, 뒤로 가기 처리 |
| 서버 데이터 | TanStack Query | 목록·상세·작업함·PDF 초안의 조회·오류·재요청·캐시 통일 |
| 전역 UI 상태 | React Context | Toast와 앱 셸처럼 작은 전역 상태만 처리 |
| 폼 상태 | 페이지 local state | 검색 초안·관심 조건·PDF 편집 초안은 화면에 귀속 |
| 스타일 | CSS Modules + `tokens.css` | 전역 선택자 충돌을 방지하고 현재 스타일 규칙 유지 |

Zustand·Redux는 도입하지 않는다. 서버 데이터는 Query, 경로 상태는 Router, 입력은 화면 local state로 구분하면 현재 범위에 충분하다.

## 4. 디렉터리 구조

```text
web/
  src/
    app/                 # App, routes, providers
    layouts/             # PublicLayout, AdminLayout
    pages/               # 사용자·관리자 페이지
    components/
      ui/                # 업무 의미 없는 공통 UI
      feedback/          # ToastProvider, ToastRegion
    features/
      announcements/
      preferences/
      programs/
      collection/
      work-items/
      document-import/
    api/                 # fetch client, 공통 API 타입
  app.css              # 현재 화면 레이아웃·토큰
  vite.config.ts
```

`components/ui`에는 범용 UI만 둔다. 공고 카드·상세 Drawer·PDF 근거 검사기처럼 업무 의미가 있는 UI는 해당 `features/` 내부에 둔다.

## 5. 공통 UI 컴포넌트

| 컴포넌트 | 책임 | 재사용 화면 |
| --- | --- | --- |
| `Button` | primary, secondary, danger, text / loading / disabled / 크기 | 전체 |
| `LinkButton` | 내부 이동 링크를 버튼 모양으로 제공 | 전체 공고 보기, PDF 등록 등 |
| `PageHeader` | eyebrow, 제목, 설명, 우측 주 행동 | 모든 페이지 |
| `SectionHeader` | 섹션 제목, 설명, 보조 행동 | 홈, 관리자, PDF 검토 |
| `FormField` | label, help, error, required와 입력 요소 연결 | 전체 폼 |
| `NumericField` | 숫자 허용, 쉼표 표시, API 숫자값 변환, 단위 | 검색, 관심 조건, PDF 검토 |
| `RegionSelector` | 시·도 → 시·군·구 → 읍·면·동 의존 선택 | 공고 검색 |
| `StatusBadge` | 내부 코드의 한글 라벨·의미 색상 표시 | 공고, 작업함, PDF 분석 |
| `Tabs` | tablist, 방향키 이동, 패널 연결 | Drawer, PDF 검토 |
| `EmptyState` | 빈 결과와 다음 행동 안내 | 목록·작업함·PDF 목록 |
| `ErrorState` | 오류 설명과 재시도 행동 | API 조회 화면 |
| `LoadMoreButton` | 다음 페이지 요청, 로딩·종료 상태 | 공고·제도·작업함·PDF |
| `ToastProvider` | 성공·오류·재시도 메시지와 aria-live | 전체 |

범용 `Card` 컴포넌트는 만들지 않는다. 단순 표면 스타일만 공유하고, 공고·작업·PDF 이력의 구조와 행동은 각 도메인 컴포넌트에 남긴다.

## 6. 공고 도메인

```text
features/announcements/
  AnnouncementCard.tsx
  AnnouncementFactGrid.tsx
  AnnouncementActions.tsx
  AnnouncementFilterForm.tsx
  RegionSelector.tsx
  AnnouncementList.tsx
  AnnouncementDrawer.tsx
  AnnouncementSummaryTab.tsx
  AnnouncementHousingTab.tsx
  AnnouncementEligibilityTab.tsx
  AnnouncementMapTab.tsx
  announcement.api.ts
  announcement.types.ts
  announcement.formatters.ts
```

- `AnnouncementCard`는 홈 추천·최근 공고와 전체 공고 목록에서 공통 사용한다.
- 카드 순서는 공고명, 지역, 보증금·월세·전용면적 2×2, 접수 기간·모집 상태·공급기관, 행동 버튼이다.
- 값이 없으면 추정하지 않고 `공고문 확인`으로 표시한다.
- 카드 제목·메타·버튼은 정해진 높이와 말줄임을 적용한다.
- 입력 중 조건은 `draftFilters`, 검색 버튼을 눌러 적용한 조건은 URL Search Params의 `appliedFilters`로 보관한다.
- Drawer의 열림 상태는 URL `announcementId`로 관리한다. Escape·backdrop·포커스 복귀·모바일 전체 폭 전환을 유지한다.
- 지도는 지도 탭을 처음 열 때만 요청하고 대상 공고 변경 또는 Drawer 닫기 시 취소한다.

## 7. 관리자·PDF 도메인

```text
features/admin/
  CollectionSummary.tsx
  CollectionSourceList.tsx
  WorkItemSummary.tsx
  WorkItemFilter.tsx
  WorkItemRow.tsx

features/document-import/
  PdfUploadForm.tsx
  ParserSelector.tsx
  ExistingAnnouncementSelector.tsx
  AnalysisProgress.tsx
  ReviewWorkspace.tsx
  PdfPreview.tsx
  ReviewSectionTabs.tsx
  EvidenceInspector.tsx
  EvidenceActionButtons.tsx
  RevisionHistory.tsx
  RevisionDiff.tsx
```

- `AdminLayout`은 관리자 메뉴와 사용자 화면 이동만 제공한다.
- 작업함 행은 제목, 요약, 한글 상태, 다음 행동 하나만 표시한다.
- PDF 원문, 편집 영역, 근거 영역을 독립 컴포넌트로 분리한다.
- 데스크톱에서는 PDF 원문을 고정하고 편집·근거 영역만 스크롤하는 현재 사용성을 보존한다.
- 원문 확인, 직접 수정, 검증, 확정, 이력 비교·복원은 기존 API와 검증 순서를 유지한다.

## 8. 라우팅

| 경로 | 레이아웃 | 페이지 |
| --- | --- | --- |
| `/` | `PublicLayout` | 홈 |
| `/announcements` | `PublicLayout` | 공고 찾기 |
| `/preferences` | `PublicLayout` | 관심 조건 |
| `/programs` | `PublicLayout` | 제도 안내 |
| `/admin` | `AdminLayout` | 수집 관리 |
| `/admin/work-items` | `AdminLayout` | 공고 데이터 관리 |
| `/admin/imports` | `AdminLayout` | PDF 분석 관리 |
| `/admin/import` | `AdminLayout` | PDF 등록 |
| `/admin/import/:extractionId` | `AdminLayout` | PDF 검토 |

기존 주소는 전환 완료 뒤에도 리디렉션한다.

| 기존 주소 | 전환 후 |
| --- | --- |
| `/admin.html` | `/admin` |
| `/admin-data.html` | `/admin/work-items` |
| `/admin-imports.html` | `/admin/imports` |
| `/admin-import.html?id={id}` | `/admin/import/{id}` |
| `/#announcements` | `/announcements` |

최종 전환 시 Spring Boot는 `/api/**`와 정적 파일 요청을 제외한 React 경로를 `index.html`로 반환한다.

## 9. 스타일과 상태 경계

- 현재 색·타이포그래피·간격 토큰과 화면 레이아웃은 `web/src/app.css`에 둔다.
- 공통 Button·FormField·Toast 등은 `web/src/components/ui.module.css`로 격리한다.
- 전역 클래스는 최소화하고, 같은 범용 선택자를 여러 CSS에서 재정의하지 않는다.
- 화면 코드가 더 커질 때 `app.css`를 기능 단위 CSS Module로 나누되, 현재 스타일 가이드의 토큰값을 유지한다.

| 상태 | 보관 위치 |
| --- | --- |
| API 목록·상세·작업함·PDF 초안 | TanStack Query |
| 검색 적용 조건 | URL Search Params |
| 검색 입력 중 조건 | `AnnouncementFilterForm` local state |
| 관심 조건 저장 폼 | `PreferencesPage` local state |
| PDF 편집 초안 | `PdfImportReviewPage` local state와 PATCH 결과 |
| Toast | `ToastProvider` Context |
| Drawer 열림 공고 | URL `announcementId` |

API 모듈은 `fetch`와 공통 오류 변환만 담당한다. 컴포넌트 안에서 `document.querySelector`, `innerHTML`, 직접 이벤트 등록을 사용하지 않는다.

## 10. 점진 전환과 완료 기준

1. `web/` Vite·React·TypeScript, 토큰, 공통 UI, 라우터를 만들고 Vite에서 `/api`를 Spring `4173`으로 프록시한다.
2. 홈 → 공고 찾기 → 관심 조건 → 제도 안내 순으로 전환하고 공고 카드·필터·Drawer를 공통화한다.
3. 수집 관리 → 작업함 → PDF 목록 순으로 관리자 화면을 전환한다.
4. PDF 등록·검토·이력 화면을 전환한다.
5. 기존 URL 리디렉션, SPA fallback, Vite 빌드·Docker Node build stage를 적용하고 정적 HTML·JS·CSS를 제거한다.

완료 기준:

- 공통 버튼·폼·상태·Toast·탭·빈 화면이 단일 구현을 사용한다.
- 홈과 전체 공고에서 같은 `AnnouncementCard`를 사용한다.
- 검색은 버튼 클릭 뒤 적용되고 URL 복원·뒤로 가기가 동작한다.
- PDF 확정 뒤 사용자 카드·상세 Drawer에 최신 DB 값이 반영된다.
- `/admin`, `/admin/work-items`, `/admin/import/:id` 직접 접속·새로고침이 동작한다.
- 360px, 데스크톱, 키보드, reduced motion, `npm test`, React 테스트, `gradlew test`를 검증한다.

## 11. 기존 문서 우선순위

1. 이 문서: 프론트엔드 구조·경로·컴포넌트·상태
2. `docs/ui/style-guide.md`: 시각 토큰과 UI 사용 규칙
3. 기존 UI·PDF 설계 문서: 업무 규칙·표시 내용·접근성·API 계약
4. 기존 문서의 `public/*.html`, `*.js`, `*.css` 파일별 변경 지시: React 전환 뒤에는 역사적 구현 기록
