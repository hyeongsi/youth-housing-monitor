# Google 로그인 설정

> 상태: **구현은 완료했지만 우선순위 후순위로 보류**  
> 재개 조건: 공고 데이터 품질·변경 감지·비교·검색 기능의 운영 검증 후, 여러 기기 간 관심 공고 동기화가 필요해졌을 때

현재는 기존 브라우저 저장 기능을 기본 사용 흐름으로 유지한다. Google Cloud Client ID·Secret을 설정하지 않으면 Google 로그인 UI가 노출되지 않으며, 기존 공고 검색·관심 저장 기능에는 영향이 없다.

## 구현 방식

- Spring Security OAuth2 Authorization Code 로그인
- Google 계정의 `sub` 값을 사용자 식별자로 사용
- Google access token·refresh token은 DB에 저장하지 않음
- 관심 공고, 메모, 알림 시점, 저장 순서는 SQLite에 사용자별로 저장
- Google Client ID/Secret이 없으면 로그인 버튼을 숨기고 기존 로컬 저장 기능만 사용

## Google Cloud Console 설정

1. Google Cloud Console에서 OAuth 2.0 클라이언트 ID를 만든다.
2. 애플리케이션 유형은 `웹 애플리케이션`을 선택한다.
3. 아래 URI를 **문자 하나까지 동일하게** 승인된 리디렉션 URI에 등록한다.

| 사용 환경 | 승인된 리디렉션 URI |
| --- | --- |
| 로컬 개발 | `http://localhost:4173/login/oauth2/code/google` |
| Tailscale HTTPS 확인 | `https://<PC의-tailnet-DNS>/login/oauth2/code/google` |
| 실제 배포 | `https://<서비스-도메인>/login/oauth2/code/google` |

`redirect_uri_mismatch`가 나오면 프로토콜, 포트, 경로, 마지막 슬래시까지 등록값과 실행 URL이 같은지 확인한다.

## 실행 환경 변수

서버를 실행하는 PC 또는 배포 환경에 아래 두 값을 설정한다.

```text
GOOGLE_CLIENT_ID=Google Cloud Console에서 발급한 Client ID
GOOGLE_CLIENT_SECRET=Google Cloud Console에서 발급한 Client Secret
```

변수 설정 뒤 서버를 재시작한다. Client Secret은 Git, `.env` 파일, 브라우저 코드에 넣지 않는다.

## 확인 절차

1. 화면 오른쪽 상단에 `Google 로그인`이 표시되는지 확인한다.
2. 로그인 후 이름 또는 이메일과 `로그아웃`이 표시되는지 확인한다.
3. 관심 공고를 저장하고, 다른 브라우저에서 같은 Google 계정으로 로그인한다.
4. 공고 찾기에서 `이 기기에 저장된 관심 공고를 Google 계정과 합칠 수 있습니다.` 안내가 나오면 필요한 경우 `계정과 합치기`를 선택한다.

Google의 OAuth 웹 서버 흐름과 리디렉션 URI 규칙은 [Google 공식 문서](https://developers.google.com/identity/protocols/oauth2/web-server)를 따른다.
