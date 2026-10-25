$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath (Join-Path $taskRoot 'spring-app')
& .\gradlew.bat bootRun --no-daemon --args="--spring.main.web-application-type=none --housing.monitor.run=true"
