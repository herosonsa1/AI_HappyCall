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
  code {
    font-size: 15.5px !important;
    font-weight: 600 !important;
    background-color: #f1f5f9 !important;
    padding: 2px 6px !important;
    border-radius: 4px !important;
  }
</style>

# 히어로손해사정 AI 자동 해피콜 플랫폼 (AI_HappyCall) 작업 이력 (Work History)

---

## 2026-10-15 — [Phase 7] 물리 서버 도입 대수 확정(2대) + 3-Node 통합(`Node 1+4+5` ➔ `Node 1` 단일화) + FreePBX 17 (Asterisk 20 LTS) AudioSocket/ARI 실제 구축 및 기술 가이드(`09`) 작성

### 1. 물리 서버 도입 대수 확정 및 3-Node 단일 통합 구현
- **실제 도입 물리 서버 대수: 총 2대 (일반 CPU 서버 1대 + Dual-GPU 서버 1대)**
  1. **물리 서버 #1 (일반 CPU 통합 서버 1대 / GPU 불필요)**:
     - 기존 `Node 1`(Biz플랫폼 배당DB 폴링 & PBX 게이트웨이) + 기존 `Node 4`(Biz플랫폼 `접수번호` UPSERT + ITTM `DouzoneApiClient` 더존 쪽지 내장 + PII 마스킹) + 기존 `Node 5`(Zero-CDN 관제 웹 콘솔)를 **`Node 1: 통합 코어 서버` ([`CorePlatformServer.java`](file:///c:/myWork/workspace/scratch/AI_Call/src/com/hero/happycall/node1/CorePlatformServer.java) / `happycall-node1-core-platform.jar`, HTTP Local `18080` / Prod `8080`, 내장 AudioSocket TCP Local `19092` / Prod `9092`)** 하나로 완전 통합.
     - 동일 물리 서버 #1에 **FreePBX 17 (Asterisk 20 LTS)** 및 **PostgreSQL 16 / Redis 7.2** 동거 구성.
  2. **물리 서버 #2 (Dual-GPU AI 추론 서버 1대 — NVIDIA L40S 48GB × 2)**:
     - `GPU #0`: **`Node 2` 음성 AI 서버 (`happycall-node2-speech-ai.jar`, Port `18082`/`8082`)**
     - `GPU #1`: **`Node 3` LLM 오케스트레이터 서버 (`happycall-node3-llm-orchestrator.jar`, Port `18083`/`8083`)**
- 구 `Node 4`/`Node 5` 소스 및 스크립트를 정리하고, `build_all.bat`, `build_node1.bat`, `bk_and_start_prod.sh`, `stop_prod.sh`를 3-Node 통합 구조로 전면 갱신.

### 2. FreePBX 17 (Asterisk 20 LTS) AI 음성봇 발신 및 양방향 스트리밍 구축
- **`CorePlatformServer.java` 내장 구현**:
  - **TCP `AudioSocket` 바이너리 프로토콜 서버(`19092` / Prod `9092`)**: Asterisk `app_audiosocket` / `res_audiosocket` 규격(`0x01` UUID, `0x03` DTMF, `0x10` 16kHz Signed Linear PCM, `0x00` Hangup) 양방향 풀듀플렉스 처리기 및 실시간 루프백 검증기(`verifyAudioSocketLoopback`) 구현.
  - **Asterisk ARI 아웃바운드 발신 클라이언트**: `POST http://{freepbxHost}:8088/ari/channels` 호 발신 제어 및 `/api/freepbx/status`, `/api/freepbx/originate` 엔드포인트 구현.
- **`freepbx-setup/` 패키지 구축 (로컬 테스트 & 운영 리눅스 서버 즉시 배포용)**:
  - [`freepbx-setup/docker-compose.freepbx.yml`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/docker-compose.freepbx.yml)
  - [`freepbx-setup/install_freepbx17_linux.sh`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/install_freepbx17_linux.sh)
  - [`freepbx-setup/asterisk-etc/pjsip_custom.conf`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/asterisk-etc/pjsip_custom.conf), [`extensions_custom.conf`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/asterisk-etc/extensions_custom.conf), [`ari_additional_custom.conf`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/asterisk-etc/ari_additional_custom.conf), [`http_custom.conf`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/asterisk-etc/http_custom.conf), [`manager_custom.conf`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/asterisk-etc/manager_custom.conf)
  - [`freepbx-setup/test_local_audiosocket_call.ps1`](file:///c:/myWork/workspace/scratch/AI_Call/freepbx-setup/test_local_audiosocket_call.ps1)
- **신규 기술 산출물 추가**: [`dist/docs/09_FreePBX_AI음성봇_구축_및_연동_상세가이드.md`](file:///c:/myWork/workspace/scratch/AI_Call/dist/docs/09_FreePBX_AI음성봇_구축_및_연동_상세가이드.md) 작성 및 `01`~`08` 산출물 일괄 갱신.

---

## 2026-10-15 — [Phase 6] ITTM(ITSM) 프로젝트 더존 그룹웨어 쪽지 발송 API REA 역공학 통합 + 산출물(`.md`) 도표·다이어그램 대형 폰트 가독성 개선
- `ITTM/dist/ITSM-prod.jar` (`DouzoneApiClient.class`, Evidence ID: `ev_60d4eed27601980a8c180b713c459fc270b45a04722e63f371b60634a1ade6f9`) 역공학 분석 및 `EmbeddedDouzoneNoteClient` (`POST https://api.douzone.com/apiproxy/api02A03`, `HmacSHA256 wehago-sign`, `groupSeq: gcmsAmaranth32481`) 내장.
- 모든 `.md` 산출물 도표(`16.5px~17px`) 및 Mermaid 다이어그램(`20px~22px`) 대형 가독성 적용.

---

## 2026-10-15 — [Phase 1 ~ 5] 초기 설계, 히어로손해사정 5단계 업무 프로세스 구현, 비필수 항목 정리
- 원수사(한화·캐롯·롯데) 위탁배당 ➔ Biz플랫폼 `ASSIGNED` 감지 ➔ AI 해피콜(이름·생년월일·가입차량번호·동의) ➔ `접수번호` Key 실시간 UPSERT ➔ 배당 담당자 더존 쪽지 발송 ➔ 특이사항 통계·모니터링 웹서비스 구현.
