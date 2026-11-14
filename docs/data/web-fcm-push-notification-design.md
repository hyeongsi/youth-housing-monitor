# 웹 FCM 푸시 알림 설계

## 목적과 범위

사용자가 웹 페이지를 열지 않아도 다음 상황을 브라우저 알림으로 받을 수 있게 한다.

1. 관심 조건과 일치하는 신규 공고
2. 관심 저장 공고의 접수 마감 임박(D-3, D-1, 당일)
3. 사용자가 설정한 확인 알림 시점

이번 설계의 1차 목표는 Chrome 기반 데스크톱·Android 브라우저의 FCM 웹 푸시다. iOS는 홈 화면에 추가한 웹 앱을 포함해 별도 실기기 검증 후 지원 범위를 확정한다.

## 현재 구현 분석

이미 존재하는 서버 기능:

- `FirebasePushSender`: Firebase Admin SDK로 FCM 대상에 전송
- `NotificationDispatchService`: 수집 결과의 신규 공고 조건 매칭·중복 방지·실패 상태 기록
- `NotificationStore`: SQLite의 기기, 조건, 이벤트, 전송 이력, 앱 알림함 관리
- `MonitorRefreshCoordinator`: 수집 완료 뒤 신규 공고 알림 발송

현재 부족한 웹 기능:

- React 앱의 알림 권한 요청 UI
- Firebase Messaging 웹 SDK와 service worker
- 웹 브라우저 등록 대상의 생성·갱신·해제 API
- 관심 저장·메모·확인 알림 시점의 서버 동기화
- 마감 임박·확인 알림을 주기적으로 평가하는 작업

## 결정

### 전송 방식

FCM Web Push를 사용한다.

- 이미 Firebase Admin SDK와 FCM 전송 코드가 있으므로 새 외부 전송 서비스를 도입하지 않는다.
- 웹은 HTTPS와 service worker가 필수다. 개발은 `localhost`, 실제 기기 검증은 HTTPS Tailscale 주소 또는 배포 주소를 사용한다.
- FCM 웹 SDK의 등록 방식은 SDK 버전에 따라 FID 등록 API 또는 호환 토큰 API가 달라질 수 있으므로 `WebPushRegistration` 어댑터로 감싼다. 화면·서버는 `deliveryTarget`만 다루고 Firebase 등록 세부사항에 의존하지 않는다.

Firebase 공식 문서는 웹 메시지 수신에 service worker가 필요하고, 백그라운드 메시지에서 알림 표시·클릭 처리를 구성할 수 있다고 안내한다. VAPID 공개 키와 service worker 등록값도 웹 등록 과정에 필요하다. [Firebase 메시지 수신 문서](https://firebase.google.com/docs/cloud-messaging/web/receive-messages), [VAPID 및 service worker 등록 옵션](https://firebase.google.com/docs/reference/js/messaging.gettokenoptions)

### 권한 요청 원칙

- 첫 진입 시 자동으로 권한 팝업을 띄우지 않는다.
- 사용자가 관심 조건 화면의 `알림 켜기`를 눌렀을 때만 설명 뒤 권한을 요청한다.
- 거부 상태에서는 반복 요청하지 않고, 브라우저 설정에서 알림을 허용하는 방법만 안내한다.
- 미지원·HTTPS 미사용·service worker 등록 실패는 각각 다른 오류 문구로 표시한다.

### 개인 데이터 원칙

앱을 닫아도 개인 확인 알림을 보내려면 브라우저 localStorage만으로는 충분하지 않다. 알림을 켠 브라우저의 관심 저장·확인 시점은 서버에 동기화한다.

- 로그인 도입 전에는 브라우저 설치 단위로 관리한다.
- 브라우저별 무작위 `installationId`와 서버 발급 `registrationSecret`을 사용한다.
- `registrationSecret`은 서버에는 해시만 보관하고, 브라우저에는 localStorage에 보관한다.
- 공고 URL이나 알림 payload에는 보증금·메모 같은 민감한 개인 정보는 넣지 않는다.

## 데이터 구조

기존 Android 기기 테이블을 바로 변경하지 않고, 웹 등록용 테이블을 추가한다. 모바일 기존 기능의 회귀 위험을 줄인 뒤 후속 단계에서 공통 `push_registrations`으로 통합할 수 있다.

| 테이블 | 핵심 필드 | 역할 |
| --- | --- | --- |
| `web_push_registrations` | `id`, `installation_id`, `secret_hash`, `delivery_target`, `permission`, `active`, `last_seen_at` | 브라우저 FCM 등록 대상 |
| `web_push_preferences` | `registration_id`, `new_announcement_enabled`, `saved_deadline_enabled`, `reminder_enabled`, 조건 JSON | 브라우저별 알림 설정·조건 |
| `web_saved_announcements` | `registration_id`, `announcement_id`, `display_order`, `note` | 현재 localStorage 관심 저장·메모 동기화 |
| `web_saved_reminders` | `registration_id`, `announcement_id`, `remind_at` | 현재 localStorage 확인 알림 시점 동기화 |
| `web_push_deliveries` | `dedupe_key`, `registration_id`, `status`, `error`, `sent_at` | 같은 브라우저에 같은 알림 중복 발송 방지 |

`dedupe_key` 예시:

- `new:{announcementId}`
- `deadline:{announcementId}:D-3`
- `deadline:{announcementId}:D-1`
- `deadline:{announcementId}:D-0`
- `reminder:{announcementId}:{remindAt}`

전송 실패 재시도 큐는 이번 범위에서 만들지 않는다. `UNREGISTERED`·`INVALID_ARGUMENT`은 기존처럼 등록을 비활성화하고, 일시 실패는 전송 이력에만 남긴다.

## API 설계

### 웹 등록

`POST /api/push/web/registrations`

요청:

```json
{
  "installationId": "browser UUID",
  "deliveryTarget": "FCM registration target",
  "permission": "granted",
  "browserName": "Chrome"
}
```

응답:

```json
{
  "registrationId": "uuid",
  "registrationSecret": "one-time returned secret",
  "preferences": {}
}
```

이후 갱신·해제·개인 데이터 동기화 요청에는 다음 헤더를 사용한다.

```text
X-Web-Push-Registration: {registrationId}
Authorization: Bearer {registrationSecret}
```

### 설정 및 동기화

| API | 목적 |
| --- | --- |
| `GET /api/push/web/preferences` | 권한·설정·동기화 상태 조회 |
| `PUT /api/push/web/preferences` | 신규·마감·확인 알림 토글과 관심 조건 저장 |
| `PUT /api/push/web/saved-state` | 관심 저장 순서·메모·확인 시점 전체 동기화 |
| `PUT /api/push/web/delivery-target` | FCM 등록값 갱신 |
| `DELETE /api/push/web/registrations/current` | 알림 해제 및 대상 비활성화 |

`saved-state`는 브라우저에서 변경할 때마다 보내지 않고, 저장 화면 변경 후 1초 디바운스로 전송한다. 실패하면 localStorage를 유지하고 `동기화하지 못했습니다` 상태만 표시한다.

## 클라이언트 설계

### 파일 계획

| 파일 | 변경 내용 |
| --- | --- |
| `web/src/features/push/firebase.ts` | Firebase 초기화, 지원 여부, service worker 등록, 등록 대상 발급 어댑터 |
| `web/src/features/push/PushSettings.tsx` | 권한·등록·토글·오류 상태 UI |
| `web/public/firebase-messaging-sw.js` | 백그라운드 메시지 표시와 알림 클릭 시 공고 상세 URL 열기 |
| `web/src/api.ts` | 웹 푸시 API 호출 함수 |
| `web/src/App.tsx` 또는 관심 조건 화면 | `푸시 알림` 섹션 연결 |

### 관심 조건 화면 UI

`관심 조건` 하단에 별도 섹션을 둔다.

| 상태 | 표시·동작 |
| --- | --- |
| 지원 확인 중 | `알림 지원 여부 확인 중` |
| 기본 권한 | 알림 종류 설명, `알림 켜기` 버튼 |
| 허용·등록 완료 | 상태 배지, 신규 공고·마감 임박·확인 알림 토글, `알림 끄기` |
| 권한 거부 | 브라우저 설정 안내, 재요청 버튼 없음 |
| HTTPS 또는 기능 미지원 | 현재 환경에서는 알림을 켤 수 없다는 안내 |
| 등록·동기화 실패 | 원인, `다시 시도` 버튼 |

알림 클릭은 `/announcements/{id}` 대신 현재 라우팅 구조에 맞는 `/announcements?announcementId={id}` 또는 상세 Drawer를 열 수 있는 hash/query URL로 연결한다. 구현 전에 상세 Drawer의 URL 상태 지원을 먼저 추가한다.

## 서버 작업 흐름

```text
수집 완료
  → 신규 공고: 기존 NotificationDispatchService 조건 평가
  → 웹 등록 브라우저별 조건 평가
  → dedupe_key 예약
  → FCM 전송
  → 전송 성공/실패 기록

5분 간격 스케줄러
  → web_saved_reminders.remind_at <= now 조회
  → 접수 마감 D-3/D-1/당일 대상 조회
  → 각 대상의 dedupe_key 예약
  → FCM 전송
```

마감일은 날짜만 있는 경우 KST 23:59:59까지 유효한 것으로 계산한다. 이미 마감된 공고에는 마감 임박 알림을 보내지 않는다.

## FCM payload

```json
{
  "notification": {
    "title": "접수 마감이 임박했어요",
    "body": "연신내역 루미노816 · 마감 D-1"
  },
  "data": {
    "type": "SAVED_DEADLINE",
    "announcementId": "..."
  },
  "webpush": {
    "fcm_options": {
      "link": "https://service.example/announcements?announcementId=..."
    }
  }
}
```

`link`는 HTTPS 공개 URL이어야 한다. 서비스 기본 URL은 `HOUSING_PUBLIC_BASE_URL` 환경 변수로 주입하고, 로컬 IP·HTTP 주소는 전송 대상으로 사용하지 않는다.

## 구현 순서

1. Firebase 프로젝트·웹 앱·VAPID 키·서비스 계정 설정 문서화
2. HTTPS 개발 주소와 `HOUSING_PUBLIC_BASE_URL` 설정
3. 웹 service worker와 권한 상태 UI만 구현하고, Firebase Console 테스트 알림 수신 확인
4. 웹 등록·해제 API와 SQLite 등록 테이블 구현
5. 기존 신규 공고 알림에 웹 등록 브라우저 전송 연결
6. 관심 저장·메모·확인 시점 서버 동기화
7. 5분 스케줄러로 개인 확인 알림·마감 임박 알림 구현
8. 상세 링크 URL 상태, 전송 이력 조회, 실패·해제 UX 추가

## 완료 기준

- HTTPS 환경에서 사용자가 명시적으로 권한을 허용하면 등록 상태가 보인다.
- 앱이 백그라운드인 상태에서 Firebase Console 테스트 알림을 수신한다.
- 신규 공고는 같은 브라우저에 같은 공고로 두 번 발송되지 않는다.
- 확인 알림·마감 임박은 각 dedupe key당 한 번만 발송된다.
- 알림 클릭 시 해당 공고 상세 화면으로 이동한다.
- 권한 거부·등록 해제·FCM 대상 무효화·전송 실패의 상태가 사용자와 운영 화면에 남는다.
- 기존 Android `MobileApiController`·FCM 전송 회귀 테스트가 통과한다.
