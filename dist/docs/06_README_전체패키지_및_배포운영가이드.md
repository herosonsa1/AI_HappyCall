<style>
  .markdown-body, body {
    font-family: "Pretendard", "Malgun Gothic", "Apple SD Gothic Neo", sans-serif !important;
    font-size: 17px !important;
    line-height: 1.75 !important;
    color: #0f172a !important;
  }
  h1 { font-size: 28px !important; font-weight: 800 !important; border-bottom: 2px solid #cbd5e1 !important; padding-bottom: 10px !important; }
  h2 { font-size: 23px !important; font-weight: 700 !important; margin-top: 32px !important; color: #1e293b !important; }
  h3 { font-size: 19px !important; font-weight: 700 !important; margin-top: 24px !important; color: #334155 !important; }
  table {
    width: 100% !important;
    border-collapse: collapse !important;
    font-size: 16.5px !important;
    margin: 18px 0 !important;
  }
  th {
    background-color: #f1f5f9 !important;
    color: #0f172a !important;
    font-weight: 700 !important;
    padding: 14px 16px !important;
    border: 1px solid #cbd5e1 !important;
    text-align: left !important;
  }
  td {
    padding: 13px 16px !important;
    border: 1px solid #cbd5e1 !important;
    vertical-align: top !important;
    line-height: 1.65 !important;
  }
  code, pre {
    font-size: 15.5px !important;
  }
</style>

# 히어로손해사정 AI 자동 해피콜 플랫폼 — 3-Node 통합 패키지 및 FreePBX 구축·운영 가이드

---

## 1. 비즈니스 프로세스 요약 (히어로손해사정 5단계 표준)

1. **원수사 업무배당**: 수탁 보험사(**한화손해보험, 캐롯손해보험, 롯데손해보험**)로부터 히어로손해사정 기간계시스템(**Biz플랫폼**)에 사고 조사 및 손해사정 업무 배당 (`접수번호` 채번, 예: `2026-HW-094812`).
2. **담당자 배당 상태 DB 감지 및 FreePBX 17 발신**: Biz플랫폼 배당 테이블(`TB_BIZ_CLAIM_ASSIGN`)에서 **`ASSIGNED` (담당자 배당 완료)** 감지 시, **`Node 1 통합 코어 서버`**가 **FreePBX 17 (Asterisk 20 LTS)**의 ARI(`POST :8088/ari/channels`)를 호출하여 발신하고 내장 **TCP `AudioSocket` (`Local 19092` / `Prod 9092`)**으로 양방향 16kHz PCM(`0x10`) 및 키패드 DTMF(`0x03`) 스트림을 연결합니다.
3. **AI 자동 해피콜 4대 정보 확인 및 기간계 실시간 UPSERT (`Key: 접수번호`)**:
   - AI 보이스봇이 고객에게 음성(STT) 또는 키패드(DTMF)로 **① 이름, ② 생년월일(6자리), ③ 가입차량번호(예: `123가4568`), ④ 개인정보동의**를 확인·취득.
   - 통화 종료(`0x00` Hangup) 즉시 **`접수번호`(`receipt_no`)를 고유 Key로 하여 Biz플랫폼(`TB_BIZ_HAPPYCALL_UPSERT`)에 실시간 UPSERT** (`IF-BIZ-002`).
4. **배당 담당자 더존(Douzone) 그룹웨어 쪽지 발송 (`Node 1` 내부에 ITTM `DouzoneApiClient` 내장)**:
   - 사내 **`ITTM(ITSM)` 프로젝트**([`ITSM-prod.jar`](file:///c:/myWork/workspace/scratch/ITTM/dist/ITSM-prod.jar) / [`DouzoneApiClient.java`](file:///c:/myWork/workspace/scratch/ITTM/src/main/java/com/hero/itsm/util/DouzoneApiClient.java))를 REA(`ev_60d4eed27601980a8c180b713c459fc270b45a04722e63f371b60634a1ade6f9`)로 분석하여 **`Node 1`([`CorePlatformServer.java`](file:///c:/myWork/workspace/scratch/AI_Call/src/com/hero/happycall/node1/CorePlatformServer.java)) 내부의 `EmbeddedDouzoneNoteClient`로 직접 내장**했습니다.
   - UPSERT 직후 `POST https://api.douzone.com/apiproxy/api02A03` (`HmacSHA256 wehago-sign`, `groupSeq: gcmsAmaranth32481`, `callerName: HERO`, `host: portal.herosonsa.co.kr`, `recvloginId: [배당담당자사번]`)를 호출하여 실시간 쪽지를 발송합니다 (`IF-DZ-001`).
5. **특이사항 통계 및 폐쇄망 모니터링 웹서비스 (`Node 1` 내장)**:
   - 통화 내역(화자분리 STT 전문·3줄 요약)과 특이사항(**본인·차량확인 실패 `AUTH_FAIL`, 고객 불만사항 `COMPLAINT_HIGH`, 직접통화 희망 `DIRECT_CALL_REQ`, 개인정보 미동의 `CONSENT_REJECT`, 정상완료 `NORMAL_COMPLETE`**) 통계를 기록하고 `Node 1` 내장 폐쇄망 웹 콘솔(`http://localhost:18080` / Prod `:8080`)에서 실시간 확인합니다.

---

## 2. [도표 1] 물리 서버 도입 대수(총 2대) 및 3-Node 통합 매핑표

기존 `Node 1`(배당폴링/PBX 게이트웨이) + `Node 4`(기간계 UPSERT & 더존 쪽지) + `Node 5`(관제 웹 콘솔)를 **`Node 1: 통합 코어 서버` (`happycall-node1-core-platform.jar`) 하나로 완전 통합**하여, 실제 도입해야 할 물리 서버는 **총 2대**(일반 CPU 서버 1대 + Dual-GPU 서버 1대)입니다.

| 물리 서버 구분 | 노드 ID | 독립 실행 JAR 및 전용 포트 | 핵심 담당 기능 |
| :--- | :---: | :--- | :--- |
| **물리 서버 #1**<br>*(일반 CPU 통합 서버 1대)*<br>FreePBX 17 + PG DB 동거 | **Node 1**<br>*(구 Node 1+4+5 단일 통합)* | `happycall-node1-core-platform.jar`<br>• HTTP: Local `18080` / Prod `8080`<br>• **AudioSocket TCP: Local `19092` / Prod `9092`** | 1) 원수사(한화·캐롯·롯데) Biz플랫폼 배당 DB 폴링 감시<br>2) **FreePBX 17 (Asterisk 20) ARI 발신(`:8088`) & 내장 AudioSocket(`:19092/:9092`) 양방향 PCM/DTMF 처리**<br>3) **`접수번호`(`receipt_no`) Key 기준 Biz플랫폼 실시간 UPSERT** & PII 녹취 묵음<br>4) **내장 ITTM `DouzoneApiClient` (`/apiproxy/api02A03`, `wehago-sign`) 쪽지 발송**<br>5) **Zero-CDN 폐쇄망 통합 관제 웹 콘솔** |
| **물리 서버 #2 (GPU #0)**<br>*(Dual-GPU AI 서버 1대)* | **Node 2** | `happycall-node2-speech-ai.jar`<br>• HTTP: Local `18082` / Prod `8082` | 가입차량번호(`123가4568`)·생년월일(6자리) 한국어 발음 정규화 및 Silero VAD / Faster-Whisper STT / CosyVoice 2.0 TTS |
| **물리 서버 #2 (GPU #1)**<br>*(Dual-GPU AI 서버 1대)* | **Node 3** | `happycall-node3-llm-orchestrator.jar`<br>• HTTP: Local `18083` / Prod `8083` | 4대 확인(이름·생년월일·차량번호·개인정보동의) 5단계 상태 머신, 불만/직접통화 분류, 통화 3줄 요약 (`vLLM Qwen2.5`) |

---

## 3. FreePBX 17 (Asterisk 20 LTS) 구축 패키지 (`freepbx-setup/`) 및 산출물 안내

- **FreePBX 설정 및 스크립트 패키지**: [`freepbx-setup/`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup) (빌드 시 [`dist/freepbx-setup/`](file:///c:/myWork/workspace/scratch/AI_Call/dist/freepbx-setup) 자동 포함)
  - [`docker-compose.freepbx.yml`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/docker-compose.freepbx.yml): 로컬 Windows Docker Desktop 및 Linux용 FreePBX 17 컨테이너 구성
  - [`install_freepbx17_linux.sh`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/install_freepbx17_linux.sh): 운영 Linux 물리 서버 #1 `/etc/asterisk` 설정 자동 배포 및 `fwconsole` / `asterisk` 리로드 스크립트
  - [`test_local_audiosocket_call.ps1`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/test_local_audiosocket_call.ps1): 무설치 Windows PowerShell 기반 AudioSocket(`19092`) 바이너리 핸드셰이크 및 E2E 발신 실증 스크립트
- **상세 기술 문서**: [`dist/docs/09_FreePBX_AI음성봇_구축_및_연동_상세가이드.md`](file:///c:/myWork/workspace/scratch/AI_Call/dist/docs/09_FreePBX_AI음성봇_구축_및_연동_상세가이드.md)
