# React 프론트엔드

## 실행

1. Spring Boot API를 `http://127.0.0.1:4173`에서 실행한다.
2. 이 폴더에서 `npm run dev`를 실행한다.
3. Vite 주소(기본 `http://127.0.0.1:5173`)로 접속한다.

`/api` 요청은 Vite proxy가 Spring Boot로 전달한다. 현재 `public/` 정적 화면은 전환 검증 전의 운영 기준으로 보존한다.

## 검증

```powershell
npm run build
```

React 빌드가 기존 Spring 정적 파일을 대체하는 배포 전환은 사용자·관리자·PDF 검토 기능 동등성 검증이 끝난 뒤 진행한다.
