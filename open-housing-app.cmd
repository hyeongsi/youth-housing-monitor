@echo off
setlocal
set "APP_URL=http://127.0.0.1:4173/"
set "SPRING_DIR=%~dp0spring-app"

powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing -Uri '%APP_URL%' -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }"
if not errorlevel 1 goto open_app

start "청약 알림 서버" /min cmd /c "cd /d ""%SPRING_DIR%"" && gradlew.bat bootRun --no-daemon"

for /L %%I in (1,1,60) do (
  powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing -Uri '%APP_URL%' -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }"
  if not errorlevel 1 goto open_app
  timeout /t 1 /nobreak >nul
)

echo 서버를 시작하지 못했습니다. spring-app 실행 로그를 확인해 주세요.
pause
exit /b 1

:open_app
start "" "%APP_URL%"
endlocal
