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

## 2. 프로젝트 폴더 및 `.\dist\docs` 산출물 구조

불필요한 테스트 항목 및 구버전 잔재 파일을 모두 제거하고, 실제 운영에 필수적인 소스 코드, 인프라 설정, 실행 스크립트, 배포 패키지(`dist\`) 및 설계 문서(`dist\docs\`)만 남긴 표준 디렉터리 구조입니다.

```text
C:\myWork\workspace\scratch\AI_Call\
├── build_all.bat                        # [로컬 Windows] 5대 노드 일괄 컴파일 -> .\dist 패키징 -> 기존 서비스 중지 -> 5대 노드 재기동
├── build_node1.bat ~ build_node5.bat    # [로컬 Windows] Node 1 ~ Node 5 개별 빌드 및 기동 스크립트
├── bk_and_start_prod.sh                 # [운영 Linux] 전체 또는 지정 노드 중지 -> 백업 -> 복원 -> 기동
├── stop_prod.sh                         # [운영 Linux] 전체 또는 지정 노드 중지 -> 백업
├── bk_and_start_node1.sh ~ node5.sh     # [운영 Linux] Node 1 ~ Node 5 개별 서버 배포·백업·기동 스크립트
├── stop_node1.sh ~ stop_node5.sh        # [운영 Linux] Node 1 ~ Node 5 개별 서버 중지·백업 스크립트
├── infra-configs/                       # 별도 필수 인프라 설정 파일 (Asterisk PBX, GPU STT/TTS, vLLM, DB DDL)
│   ├── pjsip_happycall.conf             # IP-PBX SIP Trunk & RFC 2833 DTMF 설정
│   ├── extensions_happycall.conf        # IP-PBX 아웃바운드 다이얼플랜 설정
│   ├── speech_config.env                # GPU #1 STT/TTS/VAD 및 차량번호 정규화 환경변수
│   ├── docker-compose.vllm.yml          # GPU #2 vLLM 추론 엔진 컨테이너 구성
│   └── init_happycall_schema.sql        # Biz플랫폼 연동 및 해피콜 통합 DB DDL 스키마
├── src/                                 # 5대 독립 실행 노드 Java 소스 및 Zero-CDN 정적 웹 리소스
│   ├── com/hero/happycall/common/NodeHttpSupport.java
│   ├── com/hero/happycall/node1/TelephonyGatewayServer.java
│   ├── com/hero/happycall/node2/SpeechAiServer.java
│   ├── com/hero/happycall/node3/LlmOrchestratorServer.java
│   ├── com/hero/happycall/node4/LegacySyncArchiverServer.java  # Biz플랫폼 UPSERT + 내장형 ITTM DouzoneApiClient
│   ├── com/hero/happycall/node5/AdminConsoleServer.java
│   └── resources/static/                # Zero-CDN 폐쇄망 모니터링 웹 UI (index.html, styles.css, app.js, default_seed.json)
└── dist/                                # [최종 배포 패키지 및 설계도면 산출물 디렉터리]
    ├── docs/                            # [산출물 및 설계도면 통합 보관 폴더]
    │   ├── 01_인프라_설계도면.md         # 5대 Node + Node 4 내장 더존 API + 별도 필수 추가 인프라 설계도면
    │   ├── 02_응용프로그램_설계도면.md    # 5대 Node 애플리케이션 + EmbeddedDouzoneNoteClient + DB ERD
    │   ├── 03_업무프로세스_다이어그램.md  # 히어로손해사정 5단계 실무 프로세스 다이어그램 (Flowchart/Sequence/FSM)
    │   ├── 04_AI_자동_해피콜_서비스_상세구축계획서.md
    │   ├── 05_AI_해피콜_서버별_상세설정_및_프로그램_생성_프롬프트_가이드.md
    │   ├── 06_README_전체패키지_및_배포운영가이드.md
    │   ├── 07_init_happycall_schema.sql
    │   └── 08_work_history.md
    ├── node1-telephony-gw/              # Node 1 독립 배포 번들 (Port 18081 / Prod 8081)
    ├── node2-speech-ai/                 # Node 2 독립 배포 번들 (Port 18082 / Prod 8082)
    ├── node3-llm-orchestrator/          # Node 3 독립 배포 번들 (Port 18083 / Prod 8083)
    ├── node4-legacy-sync/               # Node 4 독립 배포 번들 (Port 18084 / Prod 8084, ITTM DouzoneApiClient 내장)
    ├── node5-admin-console/             # Node 5 독립 배포 번들 (Port 18080 / Prod 8080)
    ├── happycall-node1-telephony-gw.jar ~ happycall-node5-admin-console.jar
    ├── bk_and_start_prod.sh
    └── stop_prod.sh
```

---

## 3. 5대 물리/논리 인프라 노드 및 포트 매핑표

| 노드 번호 | 인프라 구간 (망 구분) | 독립 실행 JAR 파일명 | 로컬 포트 | 운영 포트 | 핵심 담당 기능 |
| :---: | :--- | :--- | :---: | :---: | :--- |
| **Node 1** | 음성망 / DMZ | `happycall-node1-telephony-gw.jar` | `18081` | `8081` | 원수사(한화·캐롯·롯데) Biz플랫폼 배당 DB(`ASSIGNED`) 폴링 감시 및 30ch 발신 / RFC 2833 DTMF 수집 |
| **Node 2** | AI 음성 추론망 (GPU #1) | `happycall-node2-speech-ai.jar` | `18082` | `8082` | 가입차량번호(`123가4568`)·생년월일(6자리) 한국어 발음 정규화 및 STT / TTS / Barge-in VAD |
| **Node 3** | AI 코어 추론망 (GPU #2) | `happycall-node3-llm-orchestrator.jar` | `18083` | `8083` | 4대 확인(이름·생년월일·차량번호·개인정보동의) 상태 머신, 불만/직접통화 분류, 통화 3줄 요약 |
| **Node 4** | 기간계 연동망 *(더존 쪽지 내장)* | `happycall-node4-legacy-sync.jar` | `18084` | `8084` | **`접수번호`(`receipt_no`) Key 기준 Biz플랫폼 실시간 UPSERT** + **내장 ITTM `DouzoneApiClient`(`/apiproxy/api02A03`, `wehago-sign`) 쪽지 발송** |
| **Node 5** | 사내 업무 / 관제망 | `happycall-node5-admin-console.jar` | `18080` | `8080` | 원수사별·특이사항별(본인확인실패·고객불만·직접통화희망) 통계 및 모니터링 폐쇄망 웹 콘솔 |
