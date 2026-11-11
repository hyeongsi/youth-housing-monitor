# 기관별 PDF 파서 샘플 목록

최종 확인일: 2026-11-11

파서 개발·회귀 검증용 원본 PDF 18건을 로컬 `data/pdf-fixtures/`에 보관한다. 원본 파일은 외부 기관의 공고문이므로 Git에는 포함하지 않고, 이 목록과 원본 공고 URL만 버전 관리한다.

## 사용 규칙

- 파서별 최소 검증 샘플: 기관·주택유형이 다른 PDF 5건 이상
- PDF 교체 전: SHA-256이 바뀌는지 확인하고, 공고 URL·다운로드 일시·변경 사유를 이 문서에 기록
- 추출기 테스트에는 개인정보·신청서 첨부물이 아니라 아래의 `공고문` PDF만 사용
- 서울주거포털은 SH·LH 공고 목록을 제공하고 원본은 각 기관으로 연결한다. 따라서 같은 PDF를 중복 저장하지 않고, SH 원본 7건을 서울주거포털 경로 샘플로 함께 사용한다.

## LH: 5건

| 유형 | 로컬 파일 | 공고 원문 | SHA-256 |
| --- | --- | --- | --- |
| 국민임대 | `lh/2026-pyeongtaek-national-rental.pdf` | [평택송화 국민임대](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?aisTpCd=07&ccrCnntSysDsCd=03&mi=1026&panId=2015122300020973&uppAisTpCd=06) | `84DEAFC3E51542905B24F0514E483E5AE608DE4D73FEA864FC476FD49454C83C` |
| 행복주택 | `lh/2026-uiryeong-happy-housing.pdf` | [의령동동 행복주택](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?aisTpCd=10&ccrCnntSysDsCd=03&panId=2015122300020920&uppAisTpCd=06) | `38D060107442E4F5A995CD42BF5550E18BF27C56279D1CCA128EFFAFC774C84D` |
| 전세임대 | `lh/2026-newlywed-jeonse-rental.pdf` | [신혼·신생아 전세임대 I](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?aisTpCd=17&ccrCnntSysDsCd=03&mi=1026&panId=2015122300019550&uppAisTpCd=13) | `6F0296B0B3C67B7BC0AE4A1B2D4EE7260145196E7126248719FB9971E5C05CD1` |
| 일반 매입임대 | `lh/2026-gyeonggi-purchase-rental.pdf` | [경기남부 일반 매입임대](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?aisTpCd=26&ccrCnntSysDsCd=03&panId=2015122300020769&uppAisTpCd=13) | `A56A77A08248E77BF1F8222762DF8D295EB7C31E2EE060F6584518AADD3D2AE9` |
| 청년 매입임대 | `lh/2026-seoul-youth-purchase-rental.pdf` | [서울 청년 매입임대](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?aisTpCd=26&ccrCnntSysDsCd=03&mi=1026&panId=2015122300019646&uppAisTpCd=13) | `09A42D464770C1D0FA04A5061BF5D855C632D9C70B5AD525750DF5D4A2E46E82` |

## SH: 7건

| 유형 | 로컬 파일 | 공고 원문 | SHA-256 |
| --- | --- | --- | --- |
| 재개발임대 | `sh/2026-redevelopment-rental-general.pdf` | [2026 재개발임대 일반모집](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=310041) | `49BCDA777E6BB0CC948F47878DCCBA0C16D15712662FAEEF10036AF1DFDE8030` |
| 국민임대 | `sh/2026-national-rental.pdf` | [2026 국민임대](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=308340) | `798B386DEB6113962E48C220F2BA6DED74978370639A2CDE379EF1B76E8C9758` |
| 공공원룸 | `sh/2025-sadang-public-studio.pdf` | [사당동 공공원룸](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=299904) | `FE11A0D23A8A2CDAE82532B82E90ACF17BB24F99423B4307B207ED5745A5C44A` |
| 공공원룸 정정본 | `sh/2025-public-rental-reserve.pdf` | [사당동 공공원룸 정정](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=300255) | `1CC903B8F73CB03CEB29E5935D776B8B5E3382921C687776716EF95BD327591D` |
| 재개발임대 | `sh/2025-redevelopment-rental.pdf` | [2025 재개발임대](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=295159) | `7D375D31C09CB2A2D09D7F601776C50D75E87E631AF4906EED0E5820490C5376` |
| 공공주거환경임대 | `sh/2025-long-term-lease.pdf` | [2025 공공주거환경임대](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=294206) | `04896686914A8031053749F8DC1EBCA3A367E1C5DBFB913B4A686E5FACBAA3A7` |
| 국민임대 | `sh/2025-national-rental.pdf` | [2025 국민임대](https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/view.do?seq=292541) | `43B7351D2E8DB93A519DAB96575A50CC47AF1174EBACCEBCB1CDA7F99DFE90C3` |

## 서울 청년안심주택: 6건

| 유형 | 로컬 파일 | 공고 원문 | SHA-256 |
| --- | --- | --- | --- |
| 공공임대 2차 | `seoul-youth-safe-housing/2026-public-rental-second.pdf` | [2026년 2차](https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?boardId=6624&menuNo=400008) | `8131AC619C45A7B70B78EDB80DCEFDE6DE477F1FB3797522EA68246615D97B9E` |
| 공공임대 1차 | `seoul-youth-safe-housing/2026-public-rental-first.pdf` | [2026년 1차](https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?boardId=6485&menuNo=400008) | `20300A460EA7348C236BF1001E4DFC17908FA1DA8A4899D0C5A4DE45CDAAA308` |
| 공공임대 3차 | `seoul-youth-safe-housing/2025-public-rental-third.pdf` | [2025년 3차](https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?boardId=6401&menuNo=400008) | `8E59049E3CDC1A42CF68B6385F41365940A1E11F5C2335F98FD0D0AA241CD9EB` |
| 민간임대 최초모집 | `seoul-youth-safe-housing/2026-wangsimni-cheongajin-private-rental.pdf` | [왕십리역 청아진](https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?boardId=6586&menuNo=400008) | `C36E8D41D2AA6FF316C631FF291CA46B990FA252CF1629AD2111C36EA79C0D44` |
| 민간임대 추가모집 | `seoul-youth-safe-housing/2026-sangbong-private-rental.pdf` | [상봉역 상봉생활](https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?boardId=6664&menuNo=400008) | `E85AC8129CB4D1689A03CD713CE2747FE04F7C19DD0F9ABF7109B36C96C40279` |
| 민간임대 예비자 | `seoul-youth-safe-housing/2026-mia-private-rental.pdf` | [미아 헤리츠타워](https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?boardId=6665&menuNo=400008) | `B20EDE6A505DA530EE6E38893FD1E52C73C171CD70D0CB49161AE51E6D524B41` |

## 서울주거포털 경로 검증

서울주거포털의 [SH 공공임대 목록](https://housing.seoul.go.kr/site/main/sh/publicLease/02/list)은 SH 공고 원문으로 연결된다. 위 SH 7건 중 국민임대·재개발임대·공공원룸·공공주거환경임대를 서울주거포털 경로 샘플로 함께 사용한다.

- 목록의 공고명·유형·게시일과 원본 PDF 제목 일치
- `바로가기`로 이동한 SH 원문에서 PDF 첨부 링크를 획득할 수 있는지
- 원본 PDF의 공급 위치·주택형·임대조건·일정·신청조건을 추출할 수 있는지

청년주택·지원사업은 공공임대와 표 구조가 다르므로, 파서 구현 시 서울 청년안심주택 6건을 별도 템플릿으로 사용한다.
