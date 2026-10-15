@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
REM ============================================================================
REM [Node 1 - Unified Core Platform (FreePBX GW + BizPlatform + Douzone + Web Console)]
REM  - Target JAR       : .\dist\happycall-node1-core-platform.jar
REM  - Local HTTP Port  : 18080 (Prod: 8080)
REM  - AudioSocket Port : 19092 (Prod: 9092)
REM ============================================================================
set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"
set "CLASSES_DIR=%ROOT_DIR%build\classes"
set "DIST_DIR=%ROOT_DIR%dist"
set "DATA_DIR=%ROOT_DIR%data"
set "NODE_DATA_DIR=%DATA_DIR%\node1-core"
set "PORT=18080"

echo [BUILD_NODE1] Compiling and packaging happycall-node1-core-platform.jar...
if not exist "%CLASSES_DIR%\static" mkdir "%CLASSES_DIR%\static"
if not exist "%DIST_DIR%\node1-core-platform" mkdir "%DIST_DIR%\node1-core-platform"
if not exist "%NODE_DATA_DIR%" mkdir "%NODE_DATA_DIR%"

javac -encoding UTF-8 -source 1.8 -target 1.8 -d "%CLASSES_DIR%" "%ROOT_DIR%src\com\hero\happycall\common\NodeHttpSupport.java" "%ROOT_DIR%src\com\hero\happycall\node1\CorePlatformServer.java"
if errorlevel 1 exit /b 1
xcopy /E /I /Y "%ROOT_DIR%src\resources\static\*" "%CLASSES_DIR%\static\" >nul

jar cfe "%DIST_DIR%\happycall-node1-core-platform.jar" com.hero.happycall.node1.CorePlatformServer -C "%CLASSES_DIR%" .
copy /Y "%DIST_DIR%\happycall-node1-core-platform.jar" "%DIST_DIR%\node1-core-platform\happycall-node1-core-platform.jar" >nul

echo [STOP_NODE1] Stopping existing service on port %PORT% (and AudioSocket 19092)...
if exist "%DATA_DIR%\node1_local.pid" (
    set /p OLD_PID=<"%DATA_DIR%\node1_local.pid"
    if not "!OLD_PID!"=="" taskkill /F /PID !OLD_PID! >nul 2>&1
    del /f /q "%DATA_DIR%\node1_local.pid" >nul 2>&1
)
for %%P in (18080 19092) do (
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%%P "') do (
        if not "%%p"=="0" taskkill /F /PID %%p >nul 2>&1
    )
)
ping 127.0.0.1 -n 2 >nul

echo [START_NODE1] Starting happycall-node1-core-platform.jar on port %PORT%...
powershell -NoProfile -Command "$jarPath = Join-Path $env:DIST_DIR 'happycall-node1-core-platform.jar'; $cmd = 'java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar ' + $jarPath + ' --mode=local --port=' + $env:PORT + ' --data-dir=' + $env:NODE_DATA_DIR; $r = ([wmiclass]'Win32_Process').Create($cmd, $env:ROOT_DIR); if ($r.ReturnValue -eq 0) { Set-Content -Path (Join-Path $env:DATA_DIR 'node1_local.pid') -Value $r.ProcessId -NoNewline; Write-Host (' - Started NODE1 PID: ' + $r.ProcessId + ' on Port ' + $env:PORT) }"
ping 127.0.0.1 -n 2 >nul
powershell -NoProfile -Command "try { $h = Invoke-RestMethod -Uri ('http://localhost:' + $env:PORT + '/api/health') -TimeoutSec 4; Write-Host (' [OK] ' + $h.nodeId + ' (' + $h.jarName + ') UP on port ' + $h.port) -ForegroundColor Green } catch { Write-Host ' [WARN] Starting...' -ForegroundColor Yellow }"