# 다음 세션 인수인계

## 현재 상태

- 프로젝트: 서울 청년 주거 공고 모니터
- GitHub: `https://github.com/hyeongsi/youth-housing-monitor.git` / `main`
- Spring Boot 전환 완료. 화면은 기존 `public/` 정적 파일을 Spring이 제공한다.
- 전체 공고 데이터: 2,386건 (2026-10-24 23:49 KST Java 수집 기준)
  - LH 43 / SH 1,730 / 서울주거포털 85 / 청년안심주택 528

## 실행

```powershell
cd D:\workspace\Subscription-Housing\spring-app
.\gradlew.bat bootRun --no-daemon
```

- 주소: `http://127.0.0.1:4173`
- 전체 수동 수집(웹 서버 미실행):

```powershell
.\gradlew.bat bootRun --no-daemon --args="--spring.main.web-application-type=none --housing.monitor.run=true"
```

## 핵심 구현

- `spring-app/src/main/java/kr/seoulhousing/monitor/HousingMonitorService.java`
  - Java `HttpClient`로 공식 공개 목록 수집
  - LH·SH·서울주거포털: HTML 표 행 파싱
  - 청년안심주택: JSON `resultList` 파싱
  - SHA-256 기반 공고 ID로 중복 제거
  - Spring 스케줄: **매주 월요일 오전 9시(KST)** 전체 수집 (`@Scheduled`)
- `HousingDataController.java`
  - `GET /api/overview`: 화면 데이터 전체 반환
  - `PUT /api/profile`: 로컬 프로필 메모 저장
- `public/`
  - 전체 2,386건 스크롤 목록, 출처·상태·검색 필터, 원문 링크
  - 개인 조건은 자동 필터에 사용하지 않는다.

## 데이터 파일

- `data/all-announcements.json`: 전체 공고 목록
- `data/monitor-state.json`: 수집 시각·기관별 결과 (`runner: spring`)
- `data/auto-discovered.json`: 직전 목록 대비 신규 공고
- `data/notifications.json`: 신규 공고·수집 오류 정보
- `data/profile.json`: 개인 로컬 메모. `.gitignore`로 제외되어 GitHub에 올리지 않는다.

## 검증 완료

- `spring-app/.\gradlew.bat test --no-daemon` 통과
- Java 수집기로 실제 전체 수집 완료 및 2,386건 확인
- `/api/overview`에서 `runner=spring`, `items=2386` 응답 확인

## 주의·미완료

- 상세 비용·보증금·월세·입주일은 전체 목록에 자동 수집하지 않는다. 원문을 수동 검증한 6건만 `data/notices.json` 카드에 있다.
- 수집기는 공개 목록만 읽으며, 로그인·청약 제출·캡차 우회는 하지 않는다.
- Spring 스케줄러는 서버 프로세스가 계속 실행 중일 때만 동작한다. Codex 9시 자동화는 사용자가 삭제했다.
- 외부 공개·Docker·도메인 설정은 **대화만 했으며 프로젝트 파일에는 적용하지 않았다**.
- 외부 공개 전에는 인증/접근 제어가 필요하다. 현재 `PUT /api/profile`은 인증이 없다.
- 기존 Node 소스(`src/*.mjs`, `package.json`)는 레거시로 남아 있으나 현재 화면/수집의 실행 경로는 Spring이다.

## Git 상태

- 마지막 푸시 커밋: `4cb807c Initial Spring housing monitor`
- 이 문서와 월요일 9시 스케줄 변경은 다음 커밋으로 푸시해야 한다.
