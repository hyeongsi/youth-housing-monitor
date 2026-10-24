# 서울 청년 주거 추적기

서울 거주 청년이 공공임대·청년 주거지원 공고를 꾸준히 살피고, 비용·자격·일정을 같은 기준으로 기록하도록 만든 로컬 도구입니다. 외부 계정이나 민감한 식별정보를 요구하지 않으며, 청약을 자동 제출하지 않습니다.

## 현재 구현된 기능

- `data/programs.json`: LH, SH, 청년안심주택, 서울시·국가 월세지원, 보증금 이자지원, 주거급여, 주택바우처, 긴급·주거취약 지원, 주택도시기금 대출을 구분해 둔 제도 데이터베이스
- `data/notices.json`: 2026-10-24에 공식 원문과 공고문으로 확인한 현재·예정 공고의 비용, 접수일, 입주 시점, 서류·자격 확인 항목
- `npm run monitor`: 개인 조건이나 제목 키워드 없이 LH·SH·서울주거포털·청년안심주택 공개 목록의 모든 접근 가능한 페이지 행을 수집해 `data/all-announcements.json`에 저장. 나머지 공식 사이트는 목록 변경을 감시
- `npm run serve`: 로컬 비교 대시보드. `http://127.0.0.1:4173`에서 열리며 프로필을 `data/profile.json`에만 저장
- `npm run calendar`: 접수 마감일과 3일 전 확인일을 `output/housing-deadlines.ics`로 내보냄. Google Calendar·Outlook 등에 가져올 수 있음
- `npm run status`: 미확인 조건과 수집 현황을 콘솔에 출력

## 실행

Java 21 이상이 필요합니다. Spring Boot와 Gradle Wrapper가 `spring-app/`에 포함되어 있습니다.

```powershell
cd spring-app
.\gradlew.bat bootRun --no-daemon
```

수동 전체 수집은 다음처럼 실행합니다.

```powershell
cd spring-app
.\gradlew.bat bootRun --no-daemon --args="--spring.main.web-application-type=none --housing.monitor.run=true"
```

대시보드의 `내 조건 메모`에는 정확한 계좌·주민등록번호·상세 주소가 아니라 소득·자산 구간, 보증금·월세 상한, 무주택 여부, 자동차 보유, 원하는 지역·통근조건만 입력하세요. 이 메모는 공고 수집·표시를 자동으로 제외하거나 정렬하지 않습니다.

## 매일 확인 방법

Spring 서버가 실행 중이면 평일 오전 9시에 전체 공개 목록을 자동 수집합니다. 수집 결과와 오류는 각각 `data/all-announcements.json`, `data/notifications.json`, `data/monitor-state.json`에 남습니다.

Windows 작업 스케줄러, 이메일, 데스크톱 알림, 메신저 연동은 아직 연결하지 않았습니다. 인증 정보·수신 주소·메신저 웹훅은 저장하지 않으며, 연결과 테스트를 마치기 전에는 “작동 중”으로 표시하지 않습니다. `output/housing-deadlines.ics`는 지금 바로 캘린더에 가져와 알림을 설정할 수 있습니다.

## 수집 범위와 한계

- 공개 목록을 저빈도로 읽을 뿐 로그인, 캡차 우회, robots/이용 제한 회피, 자동 청약은 하지 않습니다.
- LH·SH·서울주거포털·청년안심주택의 구조화된 목록은 제목·기관·지역·상태를 **어떤 개인 조건도 적용하지 않고** 수집합니다. 화면의 출처·상태·검색 필터는 사용자가 직접 보는 용도이며, 수집 제외 규칙이 아닙니다.
- 목록에 없는 비용, 관리비, 자격, 접수일, 입주일은 자동으로 추정하지 않습니다. 반드시 각 행의 공식 원문에서 확인해야 합니다.
- 서울 25개 자치구의 일회성 사업은 매년 수시로 바뀝니다. 현재 도구는 서울시 통합 청년·주거 포털을 우선 추적합니다. 자치구별 공고 주소를 `data/sources.json`에 추가하면 같은 변경 감지 흐름에 넣을 수 있습니다. 모든 자치구의 사업을 누락 없이 자동 판정한다고 주장하지 않습니다.
- 대출은 지원금이 아닙니다. 대시보드에서 대출 항목은 상환·이자·은행 심사가 필요한 별도 유형으로 기록합니다.

## 실행 환경

- Windows의 로컬 폴더에서 실행되는 Node.js 기반 웹 도구입니다. 별도 프레임워크·데이터베이스·클라우드 계정·패키지 설치가 필요 없습니다.
- 서버는 Node 내장 `http`로 `127.0.0.1:4173`에만 열립니다. 외부에 공개하지 않으며, 브라우저 UI는 정적 HTML·CSS·JavaScript입니다.
- 상태와 프로필은 이 폴더의 JSON 파일에만 저장합니다. 수집기는 Node 내장 `fetch`로 공식 공개 페이지를 저빈도로 읽고, 로그인·캡차 우회·신청 제출·외부 알림 전송을 하지 않습니다.

## 공식 기준 출처

아래 페이지는 2026-10-24에 확인했습니다. 실제 신청·계약 전에는 해당 공고문과 첨부 PDF의 최신본을 다시 확인하세요.

- [LH 청약플러스 임대주택 공고](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancList.do?mi=1026)
- [SH 주택임대 공고·공지](https://www.i-sh.co.kr/app/lay2/program/S48T1581C563/www/brd/m_247/list.do?multi_itm_seq=2)
- [서울주거포털 공공임대 목록](https://housing.seoul.go.kr/site/main/sh/publicLease/list)
- [청년안심주택 모집공고](https://soco.seoul.go.kr/youth/bbs/BMSR00015/list.do?menuNo=400008)
- [서울시 청년월세지원](https://housing.seoul.go.kr/site/main/content/sh01_060513)
- [서울시 청년 임차보증금 이자지원](https://housing.seoul.go.kr/site/main/content/sh01_040901)
- [주거급여(임차급여)](https://housing.seoul.go.kr/site/main/content/sh01_061021)
- [복지로 청년월세 지원사업](https://www.bokjiro.go.kr/ssis-tbu/twataa/wlfareInfo/moveTWAT52011M.do?wlfareInfoId=WLF00004661&wlfareInfoReldBztpCd=01)

## 2027년 12월 입주 목표 운용 원칙

1. 현재의 2026 공고는 실제 입주 가능 시점이 너무 이르면 “대체안”으로만 두고, 계약 종료 전 조기 이사가 가능한지 먼저 확인합니다.
2. 2027년 입주 목표에는 2027년 2분기부터 나오는 정기·수시 공고를 우선순위로 두되, 일정은 발표 전까지 `미정`으로 기록합니다.
3. 공고별 비용은 `보증금 + 월 임대료 + 관리비 + 보증수수료 + 통근비`로 비교합니다. 관리비가 미공개면 0원으로 간주하지 않습니다.
4. 주민등록등본·가족관계증명서·소득·재산·자동차 자료는 공고일 이후 발급본을 요구하는 사례가 많습니다. 미리 발급하기보다 발급처와 준비 여부를 점검하고, 실제 발급일은 공고문을 기준으로 잡습니다.
