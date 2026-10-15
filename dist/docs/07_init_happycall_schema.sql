-- ============================================================================
-- [히어로손해사정 AI 자동 해피콜 플랫폼 및 Biz플랫폼(기간계) · 더존 API 연계 표준 DB 스키마]
-- 대상 DBMS: PostgreSQL 16 / 기간계 Biz플랫폼 DB (핵심 연계 Key: 접수번호 receipt_no)
-- ============================================================================

-- 1. 원수사(한화·캐롯·롯데) 업무 배당 및 DB 폴링 모니터링 대상 원장 테이블 (IF-BIZ-001)
CREATE TABLE IF NOT EXISTS TB_BIZ_CLAIM_ASSIGN (
    receipt_no          VARCHAR(40) PRIMARY KEY,               -- 핵심 Key: 접수번호 (예: 2026-HW-094812)
    insurer_code        VARCHAR(16) NOT NULL,                  -- HANWHA / CARROT / LOTTE
    insurer_name        VARCHAR(40) NOT NULL,                  -- 한화손해보험 / 캐롯손해보험 / 롯데손해보험
    claim_type          VARCHAR(60) NOT NULL,                  -- 대인배상 / 대물·차량손해 / 현장출동심사 등
    cust_name           VARCHAR(60) NOT NULL,                  -- 고객(피보험자/피해자) 이름
    cust_phone_masked   VARCHAR(32) NOT NULL,                  -- 고객 연락처 (마스킹)
    birth_ymd           CHAR(6) NOT NULL,                      -- 생년월일 6자리 (예: 850515)
    vehicle_no          VARCHAR(24) NOT NULL,                  -- 가입/사고 차량번호 (예: 123가4568)
    assign_status       VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED', -- WAIT_ASSIGN / ASSIGNED (담당자 배당 완료)
    adjuster_id         VARCHAR(32) NOT NULL,                  -- 배당 담당자(조사역) 사번 (더존 ID)
    adjuster_name       VARCHAR(40) NOT NULL,                  -- 배당 담당자 성명
    adjuster_dept       VARCHAR(60) NOT NULL,                  -- 배당 담당 부서/센터
    happycall_status    VARCHAR(24) NOT NULL DEFAULT 'WAIT',   -- WAIT / DIALING / COMPLETED / ESCALATED
    assigned_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. 통화 종료 시 접수번호(receipt_no)를 Key로 기간계(Biz플랫폼)에 실시간 UPSERT되는 테이블 (IF-BIZ-002)
CREATE TABLE IF NOT EXISTS TB_BIZ_HAPPYCALL_UPSERT (
    receipt_no          VARCHAR(40) PRIMARY KEY,               -- 기간계 UPSERT Key: 접수번호
    call_id             VARCHAR(40) NOT NULL,
    insurer_code        VARCHAR(16) NOT NULL,
    insurer_name        VARCHAR(40) NOT NULL,
    cust_name           VARCHAR(60) NOT NULL,
    birth_ymd_masked    VARCHAR(20) NOT NULL,                  -- 예: 850515-*******
    vehicle_no          VARCHAR(24) NOT NULL,                  -- 가입차량번호 (예: 123가4568)
    adjuster_id         VARCHAR(32) NOT NULL,
    adjuster_name       VARCHAR(40) NOT NULL,
    name_verified_yn    CHAR(1) NOT NULL DEFAULT 'N',          -- 1) 이름 확인 여부 (Y/N)
    birth_verified_yn   CHAR(1) NOT NULL DEFAULT 'N',          -- 2) 생년월일 6자리 확인 여부 (Y/N)
    vehicle_verified_yn CHAR(1) NOT NULL DEFAULT 'N',          -- 3) 가입차량번호 확인 여부 (Y/N)
    privacy_consent_yn  CHAR(1) NOT NULL DEFAULT 'N',          -- 4) 개인·민감정보 수집·이용 동의 여부 (Y/N)
    consent_input_mode  VARCHAR(24) NOT NULL,                  -- DTMF_KEYPAD / VOICE_STT
    anomaly_type        VARCHAR(32) NOT NULL DEFAULT 'NORMAL_COMPLETE', -- NORMAL_COMPLETE / AUTH_FAIL / COMPLAINT_HIGH / DIRECT_CALL_REQ / CONSENT_REJECT
    customer_note       TEXT,                                  -- 고객 특이사항/불만/요청사항 요약
    summary_3lines      TEXT NOT NULL,                         -- AI 3줄 요약
    rec_sha256_hash     CHAR(64) NOT NULL,                     -- 녹취 무결성 SHA-256 해시
    douzone_note_sent_yn CHAR(1) NOT NULL DEFAULT 'Y',         -- 배당 담당자 더존 쪽지 발송 여부
    upserted_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. 배당 담당자 더존(Douzone) 그룹웨어 API 쪽지 알림 발송 이력 테이블 (IF-DZ-001)
CREATE TABLE IF NOT EXISTS TB_DOUZONE_NOTE_LOG (
    note_id             VARCHAR(48) PRIMARY KEY,
    receipt_no          VARCHAR(40) NOT NULL,                  -- 접수번호
    insurer_name        VARCHAR(40) NOT NULL,                  -- 원수사명
    receiver_id         VARCHAR(32) NOT NULL,                  -- 배당 담당자 더존 사번
    receiver_name       VARCHAR(40) NOT NULL,                  -- 배당 담당자 성명
    receiver_dept       VARCHAR(60) NOT NULL,                  -- 소속 센터/팀
    urgency_level       VARCHAR(16) NOT NULL DEFAULT 'NORMAL', -- NORMAL / URGENT (불만·직접통화·본인확인실패 시 URGENT)
    note_title          VARCHAR(200) NOT NULL,
    note_content        TEXT NOT NULL,
    api_status          VARCHAR(20) NOT NULL DEFAULT 'SENT_200_OK',
    sent_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. 화자 분리 및 PII 마스킹 STT 대화록 테이블
CREATE TABLE IF NOT EXISTS TB_CALL_TRANSCRIPT_STT (
    seq_no              BIGSERIAL PRIMARY KEY,
    receipt_no          VARCHAR(40) NOT NULL,
    call_id             VARCHAR(40) NOT NULL,
    turn_index          INT NOT NULL,
    speaker             VARCHAR(16) NOT NULL,                  -- AI_BOT / CUSTOMER
    input_mode          VARCHAR(20) NOT NULL,                  -- TTS / VOICE_STT / DTMF_KEYPAD
    stage_code          VARCHAR(32) NOT NULL,
    utterance_masked    TEXT NOT NULL,
    pii_detected_yn     CHAR(1) NOT NULL DEFAULT 'N',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. 원수사별·특이사항별(본인확인실패/고객불만/직접통화요청) 통계 및 일배치 대사 이력 테이블 (IF-BIZ-003)
CREATE TABLE IF NOT EXISTS TB_RECONCILIATION_LOG (
    recon_id            VARCHAR(48) PRIMARY KEY,
    recon_date          VARCHAR(24) NOT NULL,
    total_assigned      INT NOT NULL,
    biz_upserted_cnt    INT NOT NULL,
    douzone_sent_cnt    INT NOT NULL,
    anomaly_detected_cnt INT NOT NULL,
    mismatch_cnt        INT NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'COMPLETED_OK',
    executed_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
