# 히어로손해사정 AI 자동 해피콜 플랫폼 (AI_HappyCall) 작업 이력 (Work History)

---

## 2026-10-15 — [Phase 5] 비필수 기능(ONE AI 어시스턴트 · E2E 테스트 시뮬레이터) 제거 및 `.\dist\docs` 3대 설계도면·산출물 체계화

### 1. 작업 배경 및 목적
- 폐쇄망 운영 환경에 배포되는 모니터링 웹서비스(`Node 5`)에서 테스트 성격의 E2E 시뮬레이터 탭과 비필수 부가 기능인 ONE AI 어시스턴트를 제거하여, 순수 운영 관제·원장 관리·정합성 대사 기능만 남기도록 경량화.
- 재작업 완료된 산출물 문서들과 함께, **5대 서비스 Node 외에 실제 운영 환경에서 별도로 필요한 물리/소프트웨어 인프라 항목들**을 정의한 **인프라 설계도면 · 응용프로그램 설계도면 · 업무프로세스 다이어그램**을 `.\dist\docs` 하단에 통합 구축.

### 2. 주요 작업 내역
1. **`.\dist\docs` 산출물 및 3대 설계도면 구축**:
   - `01_인프라_설계도면.md`: 5대 서비스 Node(`Node 1~5`)와 별도 필수 추가 인프라(통신사 SIP Trunk 30~60ch · SBC · Asterisk 20 IP-PBX, GPU #1 STT/TTS/Silero VAD 엔진, GPU #2 vLLM 추론 컨테이너 및 로컬 가중치 스토리지, 히어로손해사정 Biz플랫폼 DB · PostgreSQL 16 HA · Redis 7.2 클러스터 · 보안 WORM NAS, 원수사 대외계 전용선, 더존 그룹웨어 API 서버, 망분리 방화벽 ACL 및 L4 스위치) 물리/논리 토폴로지 도면 및 포트 매트릭스 작성.
   - `02_응용프로그램_설계도면.md`: 5대 독립 실행 JAR 내부 컴포넌트와 외부 인프라 연동 어댑터(Adapter Layer) 설계도, 클래스 다이어그램, REST/gRPC/JDBC 프로토콜 명세, `접수번호`(`receipt_no`) Key 중심 통합 DB ERD 작성.
   - `03_업무프로세스_다이어그램.md`: 히어로손해사정 5단계 실무 프로세스 총괄 흐름도, End-to-End 시퀀스 다이어그램, 5단계 대화 상태 머신(FSM) 및 특이사항(`AUTH_FAIL`, `COMPLAINT_HIGH`, `DIRECT_CALL_REQ`, `CONSENT_REJECT`, `NORMAL_COMPLETE`) 분기 다이어그램, DLQ 재시도 및 일배치 대사(`IF-BIZ-003`) 복구 다이어그램 작성.
   - `04_AI_자동_해피콜_서비스_상세구축계획서.md`, `05_AI_해피콜_서버별_상세설정_및_프로그램_생성_프롬프트_가이드.md`, `06_README_전체패키지_및_배포운영가이드.md`, `07_init_happycall_schema.sql` 통합 보관.
2. **웹 UI 및 프로젝트 폴더 불필요/테스트 항목 전수 제거**:
   - `src/resources/static/index.html`, `app.js`, `styles.css`에서 `E2E 시뮬레이터 탭(tab-simulator)`, `ONE AI 어시스턴트 우측 도킹 패널(#oneAiDock)`, 상단 `Ctrl+K` 자연어 커맨드 바 및 관련 스크립트/스타일 제거.
   - 임시 벤치마크 파일(`rea_benchmark/`), 구버전 단일 서버 클래스(`HappyCallServer.java`), 구버전 파이썬 프로토타입(`modules/`), 구버전 HTML 뷰어 및 로그 파일 삭제.
   - `build_all.bat`을 재실행하여 5대 독립 노드 JAR(`happycall-node1~5.jar`) 재패키징 및 기동 상태(`Port 18080~18084` 전수 `UP`) 검증 완료.

---

## 2026-10-15 — [Phase 4] 히어로손해사정 5단계 실무 업무 프로세스 맞춤형 전면 재구축

### 1. 작업 배경 및 요구사항
히어로손해사정 실제 손해사정 업무 흐름에 맞춰 전체 계획서, 설계서, DB 스키마, 5대 서버 및 웹 콘솔을 재설계 및 재구현:
1. **원수사 업무배당**: 수탁 보험사(**한화손해보험, 캐롯손해보험, 롯데손해보험**)로부터 히어로손해사정 기간계시스템(**Biz플랫폼**)에 업무 배당 (`접수번호` 채번, 예: `2026-HW-094812`).
2. **Biz플랫폼 DB 모니터링**: 배당 테이블(`TB_BIZ_CLAIM_ASSIGN`)을 실시간 감시하다가 데이터가 들어오고 **담당자 배당 완료 상태(`ASSIGNED`)**가 되면 즉시 발신 트리거 (`IF-BIZ-001`).
3. **AI 자동 해피콜 발신 및 기간계 실시간 UPSERT (`Key: 접수번호`)**:
   - AI 음성봇이 고객에게 발신하여 **① 이름(`cust_name`), ② 생년월일 6자리(`birth_ymd`), ③ 가입차량번호(`vehicle_no`, 예: `123가4568`), ④ 개인정보동의(`privacy_consent_yn`)**를 음성(STT) 또는 키패드(RFC 2833 DTMF)로 확인.
   - 통화 종료 즉시 **`접수번호`(`receipt_no`)를 고유 Key로 하여 Biz플랫폼(`TB_BIZ_HAPPYCALL_UPSERT`)에 실시간 UPSERT** (`IF-BIZ-002`).
4. **배당 담당자 더존(Douzone) API 쪽지 알림 발송**:
   - 배당된 손해사정 조사역(`adjuster_id`)에게 **더존 그룹웨어 API (`IF-DZ-001`)**를 호출하여 해피콜 완료 결과 및 긴급 특이사항 쪽지를 실시간 발송.
5. **특이사항 통계 및 폐쇄망 모니터링 웹서비스**:
   - 통화 내역(화자분리 STT 전문·3줄 요약·PII 묵음 녹취 해시)과 특이사항(**본인·차량확인 실패 `AUTH_FAIL`, 고객 불만사항 `COMPLAINT_HIGH`, 직접통화 희망 `DIRECT_CALL_REQ`, 미동의 `CONSENT_REJECT`, 정상완료 `NORMAL_COMPLETE`**) 통계를 기록하고 폐쇄망 웹 콘솔(`Node 5`)에서 확인.

### 2. 주요 수정 및 구현 파일
- `infra-configs/init_happycall_schema.sql`: `TB_BIZ_CLAIM_ASSIGN`, `TB_BIZ_HAPPYCALL_UPSERT` (`PK: receipt_no`), `TB_DOUZONE_NOTE_LOG`, `TB_CALL_TRANSCRIPT_STT`, `TB_RECONCILIATION_LOG` 스키마 구현.
- `src/com/hero/happycall/node1/TelephonyGatewayServer.java`: Biz플랫폼 `ASSIGNED` 배당 감지 및 Dual-Input(DTMF 최우선) 게이트웨이 구현.
- `src/com/hero/happycall/node2/SpeechAiServer.java`: 가입차량번호(`123가4568`) 및 생년월일 6자리 한국어 발음 정규화 및 STT/TTS 파이프라인 구현.
- `src/com/hero/happycall/node3/LlmOrchestratorServer.java`: 4대 확인 슬롯 검증 5단계 상태머신(`S1_GREETING` ~ `S5_CLOSING`), 3줄 요약 및 5대 특이사항 분류 구현.
- `src/com/hero/happycall/node4/LegacySyncArchiverServer.java`: `/api/bizplatform/upsert` (`Key: 접수번호`) 및 `/api/douzone/note/send` (`IF-DZ-001` 더존 쪽지 발송), PII 마스킹/SHA-256 아카이빙 구현.
- `src/com/hero/happycall/node5/AdminConsoleServer.java` 및 `src/resources/static/*`: 원수사별(한화·캐롯·롯데) 및 특이사항별 대시보드, 접수번호 UPSERT 원장 그리드, 컨텍스트 유지형 우측 슬라이드 드로어(UPSERT 상세·더존 쪽지 재발송·STT 대화록), 일배치 정합성 대사 화면 구현.

---

## 2026-10-15 — [Phase 3] 5-Node 물리/논리 망분리 마이크로서비스 분리 및 폐쇄망 배포 자동화

### 1. 작업 배경
- 단일 모놀리식 서버 구성을 지양하고, 실제 운영 환경의 보안 망분리(음성 DMZ망 · AI 음성추론망 · AI 코어추론망 · 기간계 연동망 · 사내 업무망)에 맞춰 **5개의 독립 실행 JAR 파일과 전용 포트, 개별 빌드(`.bat`) 및 운영 배포·백업·복원(`.sh`) 스크립트**로 완전 분리.

### 2. 노드별 분리 명세
| 노드 | 독립 실행 JAR | 로컬 포트 | 운영 포트 | 빌드/배포 스크립트 |
| :--- | :--- | :---: | :---: | :--- |
| **Node 1** | `happycall-node1-telephony-gw.jar` | `18081` | `8081` | `build_node1.bat` / `bk_and_start_node1.sh` / `stop_node1.sh` |
| **Node 2** | `happycall-node2-speech-ai.jar` | `18082` | `8082` | `build_node2.bat` / `bk_and_start_node2.sh` / `stop_node2.sh` |
| **Node 3** | `happycall-node3-llm-orchestrator.jar` | `18083` | `8083` | `build_node3.bat` / `bk_and_start_node3.sh` / `stop_node3.sh` |
| **Node 4** | `happycall-node4-legacy-sync.jar` | `18084` | `8084` | `build_node4.bat` / `bk_and_start_node4.sh` / `stop_node4.sh` |
| **Node 5** | `happycall-node5-admin-console.jar` | `18080` | `8080` | `build_node5.bat` / `bk_and_start_node5.sh` / `stop_node5.sh` |

- 통합 스크립트:
  - `[로컬 Windows] build_all.bat`: 5대 노드 컴파일 ➔ `.\dist` 폴더에 JAR 및 노드별 배포 번들 생성 ➔ 기존 기동 서비스 중지 ➔ 5대 노드 백그라운드 기동 및 헬스체크.
  - `[운영 Linux] bk_and_start_prod.sh`: 기동 중인 서비스 중지 ➔ `data/` 디렉터리 `tar.gz` 백업 ➔ 백업 복원 및 서비스 기동.
  - `[운영 Linux] stop_prod.sh`: 기동 중인 서비스 안전 중지(`SIGTERM` ➔ `SIGKILL`) ➔ 기동 데이터 백업.

---

## 2026-10-15 — [Phase 1 & 2] AI 자동 해피콜 아키텍처 심층 설계 및 엔터프라이즈 UI 표준 수립

### 1. 초기 아키텍처 및 UI 표준 정립
- 온프레미스 폐쇄망 기반 Full-Duplex 음성 파이프라인(SIP/RTP + RFC 2833 DTMF + Silero VAD + STT + vLLM Guided JSON + TTS) 목표 지연 시간(`E2E < 1,000ms`) 설계.
- 외부 인터넷/CDN 의존성이 전혀 없는 **100% Zero-CDN 자체 포함(Self-contained)** 정적 리소스 및 라이트 테마(Crisp Light Bento Grid), JENNIFER 5 X-View 산점도, WhaTap 30채널 매트릭스, 컨텍스트 유지형 우측 슬라이드 드로어 UI/UX 표준 적용.
