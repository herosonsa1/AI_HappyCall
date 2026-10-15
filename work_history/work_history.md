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

## 2026-10-15 — [Phase 6] ITTM(ITSM) 프로젝트 더존 그룹웨어 쪽지 발송 API REA 역공학 및 Node 4 내부 무노드(Zero-Extra-Node) 통합 + 산출물(`.md`) 도표·다이어그램 대형 폰트 가독성 개선

### 1. 작업 배경 및 목적
- 사내 **`ITTM(ITSM)` 프로젝트**(`C:\myWork\workspace\scratch\ITTM\`) 내의 더존 그룹웨어 쪽지 발송 API 기능을 **REA(`open_binary` & `inspect_artifact`)**로 정밀 분석하여, 별도의 중계 노드를 신설하지 않고 해당 기능과 가장 밀접한 **`Node 4` (`LegacySyncArchiverServer.java` - 통화 종료 후 `접수번호` Key 기준 Biz플랫폼 실시간 UPSERT 수행 노드)** 내부에 직접 통합 구현.
- `.\dist\docs` 하단의 모든 `.md` 산출물 파일 내 도표(Table)와 그림/다이어그램(Mermaid)의 폰트가 작게 축소되어 보이던 문제를 해결하기 위해, **Mermaid `themeVariables` 대형 폰트(`20px`~`22px`) 강제 지정**, **가로 폭 압축 방지를 위한 수직형(`flowchart TD`) 및 2단 분할 시퀀스 다이어그램 재구성**, **Markdown 프리뷰어용 대형 타이포그래피 `<style>`(`17px`) 적용** 수행.

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

---

## 2026-10-15 — [Phase 5] 비필수 기능(ONE AI 어시스턴트 · E2E 테스트 시뮬레이터) 제거 및 `.\dist\docs` 3대 설계도면·산출물 체계화
- `01_인프라_설계도면.md`, `02_응용프로그램_설계도면.md`, `03_업무프로세스_다이어그램.md` 등 `.\dist\docs` 통합 구축 및 불필요/테스트 항목 전수 제거.

---

## 2026-10-15 — [Phase 4] 히어로손해사정 5단계 실무 업무 프로세스 맞춤형 전면 재구축
- 원수사(한화·캐롯·롯데) 위탁배당 ➔ Biz플랫폼 `ASSIGNED` 감지 ➔ AI 해피콜(이름·생년월일·가입차량번호·동의) ➔ `접수번호` Key 실시간 UPSERT ➔ 배당 담당자 더존 쪽지 발송 ➔ 특이사항 통계·모니터링 웹서비스 구현.

---

## 2026-10-15 — [Phase 3] 5-Node 물리/논리 망분리 마이크로서비스 분리 및 폐쇄망 배포 자동화
- `happycall-node1~5.jar` (Port `18080~18084` / Prod `8080~8084`), `build_all.bat`, `bk_and_start_prod.sh`, `stop_prod.sh` 분리 구축.

---

## 2026-10-15 — [Phase 1 & 2] AI 자동 해피콜 아키텍처 심층 설계 및 엔터프라이즈 UI 표준 수립
- 온프레미스 폐쇄망 Full-Duplex 음성 파이프라인(`E2E < 1,000ms`) 및 100% Zero-CDN 라이트 테마 엔터프라이즈 UI/UX 표준 수립.
