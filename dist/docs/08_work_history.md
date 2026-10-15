# 히어로손해사정 AI 자동 해피콜 플랫폼 (AI_HappyCall) 작업 이력 (Work History)

---

## 2026-10-15 — [Phase 6] ITTM(ITSM) 프로젝트 더존 그룹웨어 쪽지 발송 API REA 역공학 및 Node 4 내부 무노드(Zero-Extra-Node) 통합

### 1. 작업 배경 및 목적
- 사내 **`ITTM(ITSM)` 프로젝트**(`C:\myWork\workspace\scratch\ITTM\`) 내의 더존 그룹웨어 쪽지 발송 API 기능을 **REA(`open_binary` & `inspect_artifact`)**로 정밀 분석하여, 별도의 중계 노드를 신설하지 않고 해당 기능과 가장 밀접한 **`Node 4` (`LegacySyncArchiverServer.java` - 통화 종료 후 `접수번호` Key 기준 Biz플랫폼 실시간 UPSERT 수행 노드)** 내부에 직접 통합 구현.
- 변경된 아키텍처를 반영하여 `.\dist\docs` 하단의 인프라 설계도면, 응용프로그램 설계도면, 업무프로세스 다이어그램, 구축계획서, 서버 설정 가이드, DB DDL 스키마 및 실행 JAR 배포본을 전수 갱신.

### 2. REA 역공학 분석 결과 (`ITTM/dist/ITSM-prod.jar` & `DouzoneApiClient.java`)
- **REA Evidence ID**: `ev_60d4eed27601980a8c180b713c459fc270b45a04722e63f371b60634a1ade6f9` (Parent: `ev_04c1a3f256307f9a1541e670d15e7d95ee3937eee63e80e1dec88006d976f59e`, `SHA-256: 1b077a0716fe079403251716863e669b6c7f042b2f849b102be5d721f2018fdb`)
- **핵심 구현 규격 추출**:
  1. **JVM Restricted Header 허용**: `System.setProperty("sun.net.http.allowRestrictedHeaders", "true");` (`HttpURLConnection`에서 `host: portal.herosonsa.co.kr` 헤더 수동 설정 허용)
  2. **API 엔드포인트 및 타임아웃**: `POST https://api.douzone.com/apiproxy/api02A03` (`ConnectTimeout = 3,000ms`, `ReadTimeout = 5,000ms`)
  3. **더존 인증 상수**:
     - `MSG_ACCESS_TOKEN = "NRliit56aV9bA3DFQNCzP4IgV6BVIl"`
     - `MSG_HASH_KEY = "16254241317765052739781095052517159986033643"`
     - `GROUP_SEQ = "gcmsAmaranth32481"` / `MSG_CALLER_NAME = "HERO"` / `MSG_LOGIN_ID = "admin"`
  4. **`wehago-sign` HmacSHA256 서명 생성**:
     - `transactionId`: `UUID.randomUUID().toString().replace("-", "").substring(0, 30)` (30자)
     - `timeStamp`: `String.valueOf(System.currentTimeMillis())` (Epoch Millis)
     - `wehagoSign`: `Base64(HmacSHA256(MSG_ACCESS_TOKEN + transactionId + timeStamp + "/apiproxy/api02A03", MSG_HASH_KEY))`
  5. **HTTP 요청 헤더 8종**: `Content-Type`, `callerName: HERO`, `Authorization: Bearer ...`, `transaction-id`, `timestamp`, `groupSeq: gcmsAmaranth32481`, `wehago-sign`, `host: portal.herosonsa.co.kr`
  6. **HTTP 요청 JSON 본문**: `header` (`loginId: admin`, `groupSeq: gcmsAmaranth32481`, `empSeq: admin`) + `body` (`secuYn: N`, `recvloginId: [배당담당자사번]`, `contentType: 0`, `content: sanitizeMessageLinks(본문)`, `callerName: HERO`)

### 3. 주요 코드 및 산출물 갱신 내역
- `src/com/hero/happycall/node4/LegacySyncArchiverServer.java`: 별도 노드 없이 `Node 4` 내부에 `EmbeddedDouzoneNoteClient` 정적 클래스를 내장하여, Biz플랫폼 UPSERT 직후 및 `/api/douzone/note/send` 호출 시 `wehago-sign` 서명 생성과 `/apiproxy/api02A03` 발송을 즉시 수행하도록 구현.
- `src/com/hero/happycall/node5/AdminConsoleServer.java`, `src/resources/static/index.html`, `app.js`, `default_seed.json`: 웹 콘솔 KPI, 인터페이스 명세표(`IF-DZ-001`), 우측 슬라이드 드로어에 Node 4 내장 `/apiproxy/api02A03` (`groupSeq: gcmsAmaranth32481`, `wehago-sign`) 규격 및 실시간 재발송 서명값 표시 반영.
- `dist/docs/01_인프라_설계도면.md` ~ `08_work_history.md`: 모든 설계도면과 산출물 문서 전수 갱신.

---

## 2026-10-15 — [Phase 5] 비필수 기능(ONE AI 어시스턴트 · E2E 테스트 시뮬레이터) 제거 및 `.\dist\docs` 3대 설계도면·산출물 체계화

### 1. 작업 배경 및 목적
- 폐쇄망 운영 환경에 배포되는 모니터링 웹서비스(`Node 5`)에서 테스트 성격의 E2E 시뮬레이터 탭과 비필수 부가 기능인 ONE AI 어시스턴트를 제거하여, 순수 운영 관제·원장 관리·정합성 대사 기능만 남기도록 경량화.
- 재작업 완료된 산출물 문서들과 함께, **5대 서비스 Node 외에 실제 운영 환경에서 별도로 필요한 물리/소프트웨어 인프라 항목들**을 정의한 **인프라 설계도면 · 응용프로그램 설계도면 · 업무프로세스 다이어그램**을 `.\dist\docs` 하단에 통합 구축.

### 2. 주요 작업 내역
1. **`.\dist\docs` 산출물 및 3대 설계도면 구축**:
   - `01_인프라_설계도면.md`: 5대 서비스 Node(`Node 1~5`)와 별도 필수 추가 인프라 물리/논리 토폴로지 도면 및 포트 매트릭스 작성.
   - `02_응용프로그램_설계도면.md`: 5대 독립 실행 JAR 내부 컴포넌트와 외부 인프라 연동 어댑터(Adapter Layer) 설계도, 클래스 다이어그램, REST/gRPC/JDBC 프로토콜 명세, `접수번호`(`receipt_no`) Key 중심 통합 DB ERD 작성.
   - `03_업무프로세스_다이어그램.md`: 히어로손해사정 5단계 실무 프로세스 총괄 흐름도, End-to-End 시퀀스 다이어그램, 5단계 대화 상태 머신(FSM) 및 특이사항(`AUTH_FAIL`, `COMPLAINT_HIGH`, `DIRECT_CALL_REQ`, `CONSENT_REJECT`, `NORMAL_COMPLETE`) 분기 다이어그램 작성.
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

---

## 2026-10-15 — [Phase 3] 5-Node 물리/논리 망분리 마이크로서비스 분리 및 폐쇄망 배포 자동화

### 1. 노드별 분리 명세
| 노드 | 독립 실행 JAR | 로컬 포트 | 운영 포트 | 빌드/배포 스크립트 |
| :--- | :--- | :---: | :---: | :--- |
| **Node 1** | `happycall-node1-telephony-gw.jar` | `18081` | `8081` | `build_node1.bat` / `bk_and_start_node1.sh` / `stop_node1.sh` |
| **Node 2** | `happycall-node2-speech-ai.jar` | `18082` | `8082` | `build_node2.bat` / `bk_and_start_node2.sh` / `stop_node2.sh` |
| **Node 3** | `happycall-node3-llm-orchestrator.jar` | `18083` | `8083` | `build_node3.bat` / `bk_and_start_node3.sh` / `stop_node3.sh` |
| **Node 4** | `happycall-node4-legacy-sync.jar` | `18084` | `8084` | `build_node4.bat` / `bk_and_start_node4.sh` / `stop_node4.sh` |
| **Node 5** | `happycall-node5-admin-console.jar` | `18080` | `8080` | `build_node5.bat` / `bk_and_start_node5.sh` / `stop_node5.sh` |

---

## 2026-10-15 — [Phase 1 & 2] AI 자동 해피콜 아키텍처 심층 설계 및 엔터프라이즈 UI 표준 수립
- 온프레미스 폐쇄망 기반 Full-Duplex 음성 파이프라인(`E2E < 1,000ms`) 및 100% Zero-CDN 라이트 테마 엔터프라이즈 UI/UX 표준 수립.
