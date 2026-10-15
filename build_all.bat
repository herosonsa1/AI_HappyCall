@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

REM ============================================================================
REM [Local Windows] build_all.bat (3-Node Consolidated AI HappyCall Build & Start)
REM   - Node 1 (Core Platform = PBX GW + BizPlatform + Douzone + Web UI):
REM            happycall-node1-core-platform.jar    (HTTP 18080 / Prod 8080, AudioSocket TCP 19092 / Prod 9092)
REM   - Node 2 (Speech AI - GPU #0) :
REM            happycall-node2-speech-ai.jar        (HTTP 18082 / Prod 8082)
REM   - Node 3 (LLM Orchestrator - GPU #1):
REM            happycall-node3-llm-orchestrator.jar (HTTP 18083 / Prod 8083)
REM ============================================================================

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"

set "BUILD_DIR=%ROOT_DIR%build"
set "CLASSES_DIR=%BUILD_DIR%\classes"
set "DIST_DIR=%ROOT_DIR%dist"
set "DOCS_DIR=%DIST_DIR%\docs"
set "DATA_DIR=%ROOT_DIR%data"

echo ============================================================================
echo [BUILD_ALL] 3-Node Consolidated AI HappyCall Build, Package and Restart
echo  - Root Dir : %ROOT_DIR%
echo  - Dist Dir : %DIST_DIR%
echo  - Docs Dir : %DOCS_DIR%
echo ============================================================================

echo [STEP 1/4] Compiling 3 Consolidated Node Java Sources (JDK 1.8+ UTF-8)...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%CLASSES_DIR%"
mkdir "%CLASSES_DIR%\static"
mkdir "%CLASSES_DIR%\infra-configs"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if not exist "%DOCS_DIR%" mkdir "%DOCS_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

javac -encoding UTF-8 -source 1.8 -target 1.8 -d "%CLASSES_DIR%" ^
    "%ROOT_DIR%src\com\hero\happycall\common\NodeHttpSupport.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node1\CorePlatformServer.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node2\SpeechAiServer.java" ^
    "%ROOT_DIR%src\com\hero\happycall\node3\LlmOrchestratorServer.java"

if errorlevel 1 (
    echo [ERROR] javac compilation failed.
    exit /b 1
)

xcopy /E /I /Y "%ROOT_DIR%src\resources\static\*" "%CLASSES_DIR%\static\" >nul
xcopy /E /I /Y "%ROOT_DIR%infra-configs\*" "%CLASSES_DIR%\infra-configs\" >nul

echo [STEP 2/4] Packaging 3 Separate Executable JARs and Server Bundles in .\dist...
jar cfe "%DIST_DIR%\happycall-node1-core-platform.jar" com.hero.happycall.node1.CorePlatformServer -C "%CLASSES_DIR%" .
jar cfe "%DIST_DIR%\happycall-node2-speech-ai.jar" com.hero.happycall.node2.SpeechAiServer -C "%CLASSES_DIR%" .
jar cfe "%DIST_DIR%\happycall-node3-llm-orchestrator.jar" com.hero.happycall.node3.LlmOrchestratorServer -C "%CLASSES_DIR%" .

copy /Y "%ROOT_DIR%bk_and_start_prod.sh" "%DIST_DIR%\bk_and_start_prod.sh" >nul
copy /Y "%ROOT_DIR%stop_prod.sh" "%DIST_DIR%\stop_prod.sh" >nul
if exist "%ROOT_DIR%infra-configs\init_happycall_schema.sql" (
    copy /Y "%ROOT_DIR%infra-configs\init_happycall_schema.sql" "%DOCS_DIR%\07_init_happycall_schema.sql" >nul
)
if exist "%ROOT_DIR%freepbx-setup" (
    if not exist "%DIST_DIR%\freepbx-setup" mkdir "%DIST_DIR%\freepbx-setup"
    xcopy /E /I /Y "%ROOT_DIR%freepbx-setup\*" "%DIST_DIR%\freepbx-setup\" >nul
)

for %%d in (
    "node1-core-platform:happycall-node1-core-platform.jar:node1"
    "node2-speech-ai:happycall-node2-speech-ai.jar:node2"
    "node3-llm-orchestrator:happycall-node3-llm-orchestrator.jar:node3"
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
if exist "%ROOT_DIR%freepbx-setup" (
    if not exist "%DIST_DIR%\node1-core-platform\freepbx-setup" mkdir "%DIST_DIR%\node1-core-platform\freepbx-setup"
    xcopy /E /I /Y "%ROOT_DIR%freepbx-setup\*" "%DIST_DIR%\node1-core-platform\freepbx-setup\" >nul
)

if not exist "%DATA_DIR%\node1-core" mkdir "%DATA_DIR%\node1-core"
copy /Y "%ROOT_DIR%src\resources\static\default_seed.json" "%DATA_DIR%\node1-core\happycall_runtime_db.json" >nul

echo  - Built 3 Consolidated JARs in %DIST_DIR%:
echo    [1] happycall-node1-core-platform.jar    (HTTP 18080 / Prod 8080, AudioSocket TCP 19092 / Prod 9092)
echo    [2] happycall-node2-speech-ai.jar        (HTTP 18082 / Prod 8082)
echo    [3] happycall-node3-llm-orchestrator.jar (HTTP 18083 / Prod 8083)

echo [STEP 3/4] Stopping any currently running services on ports 18080~18084 and 19092...
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
for %%P in (18080 18081 18082 18083 18084 19092) do (
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%%P "') do (
        if not "%%p"=="0" (
            echo  - Stopping port %%P listener PID: %%p
            taskkill /F /PID %%p >nul 2>&1
        )
    )
)
ping 127.0.0.1 -n 2 >nul

echo [STEP 4/4] Starting 3 Consolidated Node JARs (Ports 18080[+AudioSocket 19092], 18082, 18083)...
powershell -NoProfile -Command "$nodes = @(@{k='node1';j='happycall-node1-core-platform.jar';p=18080;d='node1-core'}, @{k='node2';j='happycall-node2-speech-ai.jar';p=18082;d='node2-speech'}, @{k='node3';j='happycall-node3-llm-orchestrator.jar';p=18083;d='node3-llm'}); foreach ($n in $nodes) { $dd = Join-Path $env:DATA_DIR $n.d; if (-not (Test-Path $dd)) { New-Item -ItemType Directory -Path $dd -Force | Out-Null }; $jarPath = Join-Path $env:DIST_DIR $n.j; $cmd = 'java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar ' + $jarPath + ' --mode=local --port=' + $n.p + ' --data-dir=' + $dd; $r = ([wmiclass]'Win32_Process').Create($cmd, $env:ROOT_DIR); if ($r.ReturnValue -eq 0) { $pidFile = Join-Path $env:DATA_DIR ($n.k + '_local.pid'); Set-Content -Path $pidFile -Value $r.ProcessId -NoNewline; Write-Host (' - Started ' + $n.k.ToUpper() + ' (' + $n.j + ') -> Port: ' + $n.p + ', PID: ' + $r.ProcessId) } }"

ping 127.0.0.1 -n 3 >nul

powershell -NoProfile -Command "foreach ($port in @(18080,18082,18083)) { try { $h = Invoke-RestMethod -Uri ('http://localhost:' + $port + '/api/health') -TimeoutSec 4; Write-Host (' [OK] ' + $h.nodeId + ' (' + $h.jarName + ') -> Status: ' + $h.status + ', Port: ' + $h.port) -ForegroundColor Green } catch { Write-Host (' [WARN] Port ' + $port + ' check pending...') -ForegroundColor Yellow } }"

echo ============================================================================
echo [BUILD_ALL COMPLETE]
echo  - Node 1 (Core Platform + Web UI): http://localhost:18080/  (AudioSocket TCP: 19092)
echo  - Node 2 (Speech AI - GPU #0)    : http://localhost:18082/api/health
echo  - Node 3 (LLM Engine - GPU #1)   : http://localhost:18083/api/health
echo  - FreePBX Setup Package          : %DIST_DIR%\freepbx-setup
echo  - Docs and Blueprints            : %DOCS_DIR%
echo ============================================================================
exit /b 0
