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

# 히어로손해사정 AI 자동 해피콜 플랫폼 — 전체 패키지 및 배포·운영 가이드

---

## 1. 비즈니스 프로세스 요약 (히어로손해사정 5단계 표준)

1. **원수사 업무배당**: 수탁 보험사(**한화손해보험, 캐롯손해보험, 롯데손해보험**)로부터 히어로손해사정 기간계시스템(**Biz플랫폼**)에 사고 조사 및 손해사정 업무 배당 (`접수번호` 채번, 예: `2026-HW-094812`).
2. **담당자 배당 상태 DB 감지**: Biz플랫폼 배당 테이블(`TB_BIZ_CLAIM_ASSIGN`)을 실시간 모니터링하다가 상태가 **`ASSIGNED` (담당자 배당 완료)**가 되면 즉시 발신 트리거 (`IF-BIZ-001`).
3. **AI 자동 해피콜 발신 및 기간계 실시간 UPSERT (`Key: 접수번호`)**:
   - AI 보이스봇이 고객에게 전화를 걸어 음성(STT) 또는 키패드(RFC 2833 DTMF)로 **① 이름, ② 생년월일(6자리), ③ 가입차량번호(예: `123가4568`), ④ 개인정보동의**를 확인·취득.
   - 통화 종료 즉시 **`접수번호`(`receipt_no`)를 고유 Key로 하여 Biz플랫폼(`TB_BIZ_HAPPYCALL_UPSERT`)에 실시간 UPSERT** (`IF-BIZ-002`).
4. **배당 담당자 더존(Douzone) 그룹웨어 쪽지 발송 (별도 노드 없이 `Node 4` 내부에 ITTM `DouzoneApiClient` 내장)**:
   - 사내 **`ITTM(ITSM)` 프로젝트**([`ITSM-prod.jar`](file:///c:/myWork/workspace/scratch/ITTM/dist/ITSM-prod.jar) / [`DouzoneApiClient.java`](file:///c:/myWork/workspace/scratch/ITTM/src/main/java/com/hero/itsm/util/DouzoneApiClient.java))를 REA(`ev_60d4eed27601980a8c180b713c459fc270b45a04722e63f371b60634a1ade6f9`)로 분석하여 별도 노드를 두지 않고 **`Node 4`([`LegacySyncArchiverServer.java`](file:///c:/myWork/workspace/scratch/AI_Call/src/com/hero/happycall/node4/LegacySyncArchiverServer.java)) 내부의 `EmbeddedDouzoneNoteClient`로 직접 내장**했습니다.
   - UPSERT 직후 `POST https://api.douzone.com/apiproxy/api02A03` (`HmacSHA256 wehago-sign`, `groupSeq: gcmsAmaranth32481`, `callerName: HERO`, `host: portal.herosonsa.co.kr`, `recvloginId: [배당담당자사번]`)를 호출하여 실시간 쪽지를 발송합니다 (`IF-DZ-001`).
5. **특이사항 통계 및 폐쇄망 모니터링 웹서비스**:
   - 통화 내역(화자분리 STT 전문·3줄 요약)과 특이사항(**본인·차량확인 실패 `AUTH_FAIL`, 고객 불만사항 `COMPLAINT_HIGH`, 직접통화 희망 `DIRECT_CALL_REQ`, 개인정보 미동의 `CONSENT_REJECT`, 정상완료 `NORMAL_COMPLETE`**) 통계를 기록하고 폐쇄망 웹서비스(`Node 5`)에서 실시간 확인.

---

## 2. [도표 1] 5대 물리/논리 인프라 노드 및 포트 매핑표

| 노드 | 인프라 구간 (망 구분) | 독립 실행 JAR 및 포트 | 핵심 담당 기능 |
| :---: | :--- | :--- | :--- |
| **Node 1** | 음성망 / DMZ | `happycall-node1-telephony-gw.jar`<br>Local `18081` / Prod `8081` | 원수사(한화·캐롯·롯데) Biz플랫폼 배당 DB(`ASSIGNED`) 폴링 감시 및 30ch 발신 / RFC 2833 DTMF 수집 |
| **Node 2** | AI 음성 추론망 (GPU #1) | `happycall-node2-speech-ai.jar`<br>Local `18082` / Prod `8082` | 가입차량번호(`123가4568`)·생년월일(6자리) 한국어 발음 정규화 및 STT / TTS / Barge-in VAD |
| **Node 3** | AI 코어 추론망 (GPU #2) | `happycall-node3-llm-orchestrator.jar`<br>Local `18083` / Prod `8083` | 4대 확인(이름·생년월일·차량번호·개인정보동의) 상태 머신, 불만/직접통화 분류, 통화 3줄 요약 |
| **Node 4** | 기간계 연동망<br>*(더존 쪽지 내장)* | `happycall-node4-legacy-sync.jar`<br>Local `18084` / Prod `8084` | **`접수번호`(`receipt_no`) Key 기준 Biz플랫폼 실시간 UPSERT** + **내장 ITTM `DouzoneApiClient`(`/apiproxy/api02A03`, `wehago-sign`) 쪽지 발송** |
| **Node 5** | 사내 업무 / 관제망 | `happycall-node5-admin-console.jar`<br>Local `18080` / Prod `8080` | 원수사별·특이사항별(본인확인실패·고객불만·직접통화희망) 통계 및 모니터링 폐쇄망 웹 콘솔 |
