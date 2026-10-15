@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
REM ============================================================================
REM [Node 4 - PII Masking Archiver and Legacy DB Sync (APP-05/06)] Dedicated Build, Package (.jar) and Service Restart Script
REM  - Target JAR : .\dist\happycall-node4-legacy-sync.jar (and .\dist\node4-legacy-sync\happycall-node4-legacy-sync.jar)
REM  - Local Port : 18084
REM ============================================================================
set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"
set "CLASSES_DIR=%ROOT_DIR%build\classes"
set "DIST_DIR=%ROOT_DIR%dist"
set "DATA_DIR=%ROOT_DIR%data"
set "NODE_DATA_DIR=%DATA_DIR%\node4-legacy"
set "PORT=18084"

echo [BUILD_NODE4] Compiling and packaging happycall-node4-legacy-sync.jar...
if not exist "%CLASSES_DIR%\static" mkdir "%CLASSES_DIR%\static"
if not exist "%DIST_DIR%\node4-legacy-sync" mkdir "%DIST_DIR%\node4-legacy-sync"
if not exist "%NODE_DATA_DIR%" mkdir "%NODE_DATA_DIR%"

javac -encoding UTF-8 -source 1.8 -target 1.8 -d "%CLASSES_DIR%" "%ROOT_DIR%src\com\hero\happycall\common\NodeHttpSupport.java" "%ROOT_DIR%src\com\hero\happycall\node4\LegacySyncArchiverServer.java"
if errorlevel 1 exit /b 1
xcopy /E /I /Y "%ROOT_DIR%src\resources\static\*" "%CLASSES_DIR%\static\" >nul

jar cfe "%DIST_DIR%\happycall-node4-legacy-sync.jar" com.hero.happycall.node4.LegacySyncArchiverServer -C "%CLASSES_DIR%" .
copy /Y "%DIST_DIR%\happycall-node4-legacy-sync.jar" "%DIST_DIR%\node4-legacy-sync\happycall-node4-legacy-sync.jar" >nul

echo [STOP_NODE4] Stopping existing service on port %PORT%...
if exist "%DATA_DIR%\node4_local.pid" (
    set /p OLD_PID=<"%DATA_DIR%\node4_local.pid"
    if not "!OLD_PID!"=="" taskkill /F /PID !OLD_PID! >nul 2>&1
    del /f /q "%DATA_DIR%\node4_local.pid" >nul 2>&1
)
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%PORT% "') do (
    if not "%%p"=="0" taskkill /F /PID %%p >nul 2>&1
)
ping 127.0.0.1 -n 2 >nul

echo [START_NODE4] Starting happycall-node4-legacy-sync.jar on port %PORT%...
powershell -NoProfile -Command "$jarPath = Join-Path $env:DIST_DIR 'happycall-node4-legacy-sync.jar'; $cmd = 'java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar ' + $jarPath + ' --mode=local --port=' + $env:PORT + ' --data-dir=' + $env:NODE_DATA_DIR; $r = ([wmiclass]'Win32_Process').Create($cmd, $env:ROOT_DIR); if ($r.ReturnValue -eq 0) { Set-Content -Path (Join-Path $env:DATA_DIR 'node4_local.pid') -Value $r.ProcessId -NoNewline; Write-Host (' - Started NODE4 PID: ' + $r.ProcessId + ' on Port ' + $env:PORT) }"
ping 127.0.0.1 -n 2 >nul
powershell -NoProfile -Command "try { $h = Invoke-RestMethod -Uri ('http://localhost:' + $env:PORT + '/api/health') -TimeoutSec 4; Write-Host (' [OK] ' + $h.nodeId + ' (' + $h.jarName + ') UP on port ' + $h.port) -ForegroundColor Green } catch { Write-Host ' [WARN] Starting...' -ForegroundColor Yellow }"