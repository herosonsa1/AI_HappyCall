@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

REM ============================================================================
REM [Local Windows] build_all.bat (5-Node Distributed AI HappyCall Build & Start)
REM   - Node 1 (Telephony GW)   : happycall-node1-telephony-gw.jar     (Port 18081 / Prod 8081)
REM   - Node 2 (Speech AI)      : happycall-node2-speech-ai.jar        (Port 18082 / Prod 8082)
REM   - Node 3 (LLM Orchestrator): happycall-node3-llm-orchestrator.jar (Port 18083 / Prod 8083)
REM   - Node 4 (Legacy and PII) : happycall-node4-legacy-sync.jar      (Port 18084 / Prod 8084)
REM   - Node 5 (Web Console UI) : happycall-node5-admin-console.jar    (Port 18080 / Prod 8080)
REM ============================================================================

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"

set "BUILD_DIR=%ROOT_DIR%build"
set "CLASSES_DIR=%BUILD_DIR%\classes"
set "DIST_DIR=%ROOT_DIR%dist"
set "DOCS_DIR=%DIST_DIR%\docs"
set "DATA_DIR=%ROOT_DIR%data"

echo ============================================================================
echo [BUILD_ALL] 5-Node Distributed AI HappyCall Build, Package and Restart
echo  - Root Dir : %ROOT_DIR%
echo  - Dist Dir : %DIST_DIR%
echo  - Docs Dir : %DOCS_DIR%
echo ============================================================================

echo [STEP 1/4] Compiling 5 Distributed Node Java Sources (JDK 1.8+ UTF-8)...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%CLASSES_DIR%"
mkdir "%CLASSES_DIR%\static"
mkdir "%CLASSES_DIR%\infra-configs"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if not exist "%DOCS_DIR%" mkdir "%DOCS_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

javac -encoding UTF-8 -source 1.8 -target 1.8 -d "%CLASSES_DIR%" ^
    "%ROOT_DIR%src\com\hero\happycall\common\NodeHttpSupport.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node1\TelephonyGatewayServer.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node2\SpeechAiServer.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node3\LlmOrchestratorServer.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node4\LegacySyncArchiverServer.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node5\AdminConsoleServer.java"

if errorlevel 1 (
    echo [ERROR] javac compilation failed.
    exit /b 1
)

xcopy /E /I /Y "%ROOT_DIR%src\resources\static\*" "%CLASSES_DIR%\static\" >nul
xcopy /E /I /Y "%ROOT_DIR%infra-configs\*" "%CLASSES_DIR%\infra-configs\" >nul

echo [STEP 2/4] Packaging 5 Separate Executable JARs and Server Bundles in .\dist...
jar cfe "%DIST_DIR%\happycall-node1-telephony-gw.jar" com.hero.happycall.node1.TelephonyGatewayServer -C "%CLASSES_DIR%" .
jar cfe "%DIST_DIR%\happycall-node2-speech-ai.jar" com.hero.happycall.node2.SpeechAiServer -C "%CLASSES_DIR%" .
jar cfe "%DIST_DIR%\happycall-node3-llm-orchestrator.jar" com.hero.happycall.node3.LlmOrchestratorServer -C "%CLASSES_DIR%" .
jar cfe "%DIST_DIR%\happycall-node4-legacy-sync.jar" com.hero.happycall.node4.LegacySyncArchiverServer -C "%CLASSES_DIR%" .
jar cfe "%DIST_DIR%\happycall-node5-admin-console.jar" com.hero.happycall.node5.AdminConsoleServer -C "%CLASSES_DIR%" .

copy /Y "%ROOT_DIR%bk_and_start_prod.sh" "%DIST_DIR%\bk_and_start_prod.sh" >nul
copy /Y "%ROOT_DIR%stop_prod.sh" "%DIST_DIR%\stop_prod.sh" >nul
if exist "%ROOT_DIR%infra-configs\init_happycall_schema.sql" (
    copy /Y "%ROOT_DIR%infra-configs\init_happycall_schema.sql" "%DOCS_DIR%\07_init_happycall_schema.sql" >nul
)

for %%d in (
    "node1-telephony-gw:happycall-node1-telephony-gw.jar:node1"
    "node2-speech-ai:happycall-node2-speech-ai.jar:node2"
    "node3-llm-orchestrator:happycall-node3-llm-orchestrator.jar:node3"
    "node4-legacy-sync:happycall-node4-legacy-sync.jar:node4"
    "node5-admin-console:happycall-node5-admin-console.jar:node5"
) do (
    for /f "tokens=1,2,3 delims=:" %%a in (%%d) do (
        if not exist "%DIST_DIR%\%%a" mkdir "%DIST_DIR%\%%a"
        if not exist "%DIST_DIR%\%%a\data" mkdir "%DIST_DIR%\%%a\data"
        copy /Y "%DIST_DIR%\%%b" "%DIST_DIR%\%%a\%%b" >nul
        if exist "%ROOT_DIR%bk_and_start_%%c.sh" (
            copy /Y "%ROOT_DIR%bk_and_start_%%c.sh" "%DIST_DIR%\%%a\bk_and_start_prod.sh" >nul
            copy /Y "%ROOT_DIR%bk_and_start_%%c.sh" "%DIST_DIR%\%%a\bk_and_start_%%c.sh" >nul
        )
        if exist "%ROOT_DIR%stop_%%c.sh" (
            copy /Y "%ROOT_DIR%stop_%%c.sh" "%DIST_DIR%\%%a\stop_prod.sh" >nul
            copy /Y "%ROOT_DIR%stop_%%c.sh" "%DIST_DIR%\%%a\stop_%%c.sh" >nul
        )
    )
)

if not exist "%DATA_DIR%\node5-console" mkdir "%DATA_DIR%\node5-console"
copy /Y "%ROOT_DIR%src\resources\static\default_seed.json" "%DATA_DIR%\node5-console\happycall_runtime_db.json" >nul

echo  - Built 5 Separate JARs in %DIST_DIR%:
echo    [1] happycall-node1-telephony-gw.jar     (Port 18081 / Prod 8081)
echo    [2] happycall-node2-speech-ai.jar        (Port 18082 / Prod 8082)
echo    [3] happycall-node3-llm-orchestrator.jar (Port 18083 / Prod 8083)
echo    [4] happycall-node4-legacy-sync.jar      (Port 18084 / Prod 8084)
echo    [5] happycall-node5-admin-console.jar    (Port 18080 / Prod 8080)

echo [STEP 3/4] Stopping any currently running services on ports 18080 ~ 18084...
for %%k in (node1 node2 node3 node4 node5) do (
    if exist "%DATA_DIR%\%%k_local.pid" (
        set /p OLD_PID=<"%DATA_DIR%\%%k_local.pid"
        if not "!OLD_PID!"=="" (
            echo  - Stopping tracked %%k PID: !OLD_PID!
            taskkill /F /PID !OLD_PID! >nul 2>&1
        )
        del /f /q "%DATA_DIR%\%%k_local.pid" >nul 2>&1
    )
)
for %%P in (18080 18081 18082 18083 18084) do (
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%%P "') do (
        if not "%%p"=="0" (
            echo  - Stopping port %%P listener PID: %%p
            taskkill /F /PID %%p >nul 2>&1
        )
    )
)
ping 127.0.0.1 -n 2 >nul

echo [STEP 4/4] Starting 5 Independent Node JARs on Dedicated Ports (18081~18084, 18080)...
powershell -NoProfile -Command "$nodes = @(@{k='node1';j='happycall-node1-telephony-gw.jar';p=18081;d='node1-telephony'}, @{k='node2';j='happycall-node2-speech-ai.jar';p=18082;d='node2-speech'}, @{k='node3';j='happycall-node3-llm-orchestrator.jar';p=18083;d='node3-llm'}, @{k='node4';j='happycall-node4-legacy-sync.jar';p=18084;d='node4-legacy'}, @{k='node5';j='happycall-node5-admin-console.jar';p=18080;d='node5-console'}); foreach ($n in $nodes) { $dd = Join-Path $env:DATA_DIR $n.d; if (-not (Test-Path $dd)) { New-Item -ItemType Directory -Path $dd -Force | Out-Null }; $jarPath = Join-Path $env:DIST_DIR $n.j; $cmd = 'java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar ' + $jarPath + ' --mode=local --port=' + $n.p + ' --data-dir=' + $dd; $r = ([wmiclass]'Win32_Process').Create($cmd, $env:ROOT_DIR); if ($r.ReturnValue -eq 0) { $pidFile = Join-Path $env:DATA_DIR ($n.k + '_local.pid'); Set-Content -Path $pidFile -Value $r.ProcessId -NoNewline; Write-Host (' - Started ' + $n.k.ToUpper() + ' (' + $n.j + ') -> Port: ' + $n.p + ', PID: ' + $r.ProcessId) } }"

ping 127.0.0.1 -n 3 >nul

powershell -NoProfile -Command "foreach ($port in @(18081,18082,18083,18084,18080)) { try { $h = Invoke-RestMethod -Uri ('http://localhost:' + $port + '/api/health') -TimeoutSec 4; Write-Host (' [OK] ' + $h.nodeId + ' (' + $h.jarName + ') -> Status: ' + $h.status + ', Port: ' + $h.port) -ForegroundColor Green } catch { Write-Host (' [WARN] Port ' + $port + ' check pending...') -ForegroundColor Yellow } }"

echo ============================================================================
echo [BUILD_ALL COMPLETE]
echo  - Node 1 (Telephony GW)    : http://localhost:18081/api/health
echo  - Node 2 (Speech AI)       : http://localhost:18082/api/health
echo  - Node 3 (LLM Orchestrator): http://localhost:18083/api/health
echo  - Node 4 (Legacy and PII)  : http://localhost:18084/api/health
echo  - Node 5 (Web Console UI)  : http://localhost:18080/
echo  - Docs and Blueprints      : %DOCS_DIR%
echo ============================================================================
exit /b 0
