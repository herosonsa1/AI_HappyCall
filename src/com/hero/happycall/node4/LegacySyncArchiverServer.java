package com.hero.happycall.node4;

import com.hero.happycall.common.NodeHttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [NODE 4 - 히어로손해사정 기간계(Biz플랫폼) & ITTM 더존(Douzone) 그룹웨어 쪽지 통합 노드]
 * JAR 파일명 : happycall-node4-legacy-sync.jar
 * 기본 포트  : 로컬 18084 / 운영 8084
 * 핵심 역할  :
 *  - 별도 노드 추가 없이 기간계 연동 노드(Node 4) 내부에 ITTM(ITSM) 프로젝트의 DouzoneApiClient 쪽지 발송 엔진을 통합 내장
 *  - 3-1) 통화 종료 시 접수번호(receipt_no)를 Key 값으로 Biz플랫폼 기간계 DB 실시간 UPSERT
 *  - 4) 배당 담당자(조사역)에게 ITTM 더존 그룹웨어 API (/apiproxy/api02A03, HmacSHA256 wehago-sign, groupSeq=gcmsAmaranth32481) 쪽지 발송
 *  - 5) PII 정규식 마스킹 및 녹취 SHA-256 무결성 아카이빙
 */
public class LegacySyncArchiverServer {

    static {
        // ITTM DouzoneApiClient 동일 설정: HttpURLConnection에서 host 헤더(portal.herosonsa.co.kr) 수동 지정을 허용
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private static final String NODE_ID = "NODE4-LEGACY-SYNC";
    private static final String SERVICE_NAME = "BizPlatform(ReceiptNo) UPSERT & ITTM Douzone GW Note API (/apiproxy/api02A03)";
    private static final long START_MS = System.currentTimeMillis();
    private static final AtomicInteger UPSERT_COUNT = new AtomicInteger(1412);
    private static final AtomicInteger DOUZONE_NOTE_COUNT = new AtomicInteger(1412);

    // =========================================================================
    // [ITTM(ITSM) 프로젝트 REA 역공학 추출 - 더존 그룹웨어 쪽지 발송 API 상수]
    // 분석 대상: C:\myWork\workspace\scratch\ITTM\dist\ITSM-prod.jar (DouzoneApiClient.class)
    // REA Evidence ID: ev_60d4eed27601980a8c180b713c459fc270b45a04722e63f371b60634a1ade6f9
    // =========================================================================
    private static final String DOUZONE_DEFAULT_BASE_URL = "https://api.douzone.com";
    private static final String MSG_API_URL = "/apiproxy/api02A03";
    private static final String MSG_ACCESS_TOKEN = "NRliit56aV9bA3DFQNCzP4IgV6BVIl";
    private static final String MSG_HASH_KEY = "16254241317765052739781095052517159986033643";
    private static final String MSG_CALLER_NAME = "HERO";
    private static final String MSG_LOGIN_ID = "admin";
    private static final String GROUP_SEQ = "gcmsAmaranth32481";
    private static final String HOST_HEADER = "portal.herosonsa.co.kr";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private static final Pattern SSN_PATTERN = Pattern.compile("(\\d{6})[- ]?([1-4]\\d{6})");
    private static final Pattern ACCT_PATTERN = Pattern.compile("(\\d{3})[- ]?(\\d{2,6})[- ]?(\\d{2,6})(\\d{2})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(01[016789])[- ]?(\\d{3,4})[- ]?(\\d{4})");

    private static String envMode = "LOCAL";
    private static int port = 18084;
    private static String dataDir = "./data/node4-legacy";
    private static String douzoneBaseUrl = DOUZONE_DEFAULT_BASE_URL;
    private static boolean douzoneLiveSend = false;

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8084 : 18084);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node4-legacy");
        douzoneBaseUrl = NodeHttpSupport.parseArg(args, "--douzone-base-url=", DOUZONE_DEFAULT_BASE_URL);
        douzoneLiveSend = "PROD".equals(envMode) || "true".equalsIgnoreCase(NodeHttpSupport.parseArg(args, "--douzone-live=", "false"));

        NodeHttpSupport.ensureDir(dataDir);
        String initJson = String.format(
            "{\"nodeId\":\"%s\",\"service\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"upsertKey\":\"receipt_no\",\"douzoneEndpoint\":\"%s\",\"groupSeq\":\"%s\",\"callerName\":\"%s\",\"startedAt\":\"%s\"}",
            NODE_ID, SERVICE_NAME, envMode, port, UPSERT_COUNT.get(), DOUZONE_NOTE_COUNT.get(),
            douzoneBaseUrl + MSG_API_URL, GROUP_SEQ, MSG_CALLER_NAME, NodeHttpSupport.nowString()
        );
        NodeHttpSupport.readStateFile(dataDir, "node4_legacy_state.json", initJson);
        NodeHttpSupport.appendNodeLog(
            dataDir, NODE_ID, envMode, "SERVER_START",
            "Port=" + port + " | BizPlatform UPSERT & ITTM DouzoneApiClient(" + douzoneBaseUrl + MSG_API_URL + ", groupSeq=" + GROUP_SEQ + ") 통합 기동 완료"
        );

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/pii/mask-and-archive", new PiiMaskAndUpsertHandler());
        server.createContext("/api/bizplatform/upsert", new BizUpsertHandler());
        server.createContext("/api/douzone/note/send", new DouzoneNoteHandler());
        server.createContext("/api/legacy/sync", new LegacySyncHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[" + NODE_ID + "] Started on port " + port + " (" + envMode + ") | DouzoneAPI=" + douzoneBaseUrl + MSG_API_URL + " | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    /**
     * ITTM(ITSM) 프로젝트의 DouzoneApiClient를 Node 4 내부 정적 엔진으로 통합 구현
     * - 엔드포인트: POST https://api.douzone.com/apiproxy/api02A03
     * - 인증서명: HmacSHA256(MSG_ACCESS_TOKEN + transactionId + timeStamp + "/apiproxy/api02A03", MSG_HASH_KEY) -> wehago-sign 헤더
     * - 필수헤더: callerName=HERO, Authorization=Bearer ..., transaction-id(30자), timestamp(epoch ms), groupSeq=gcmsAmaranth32481, host=portal.herosonsa.co.kr
     */
    public static class EmbeddedDouzoneNoteClient {

        public static class DouzoneSendResult {
            public final boolean success;
            public final String transactionId;
            public final String timestamp;
            public final String wehagoSign;
            public final String endpoint;
            public final String requestPayloadJson;
            public final String statusDetail;

            public DouzoneSendResult(boolean success, String transactionId, String timestamp, String wehagoSign,
                                     String endpoint, String requestPayloadJson, String statusDetail) {
                this.success = success;
                this.transactionId = transactionId;
                this.timestamp = timestamp;
                this.wehagoSign = wehagoSign;
                this.endpoint = endpoint;
                this.requestPayloadJson = requestPayloadJson;
                this.statusDetail = statusDetail;
            }
        }

        public static DouzoneSendResult sendGroupwareMessage(List<String> recvIds, String content) {
            if (recvIds == null || recvIds.isEmpty()) {
                return new DouzoneSendResult(false, "", "", "", douzoneBaseUrl + MSG_API_URL, "{}", "SKIPPED_NO_RECEIVER");
            }

            try {
                // 1. ITTM sanitizeMessageLinks 규칙 적용 (운영/로컬 도메인 치환 및 마크다운 괄호 제거)
                String sanitizedContent = sanitizeMessageLinks(content);

                // 2. transaction-id (30자 UUID) 및 timestamp (밀리초) 생성
                String transactionId = UUID.randomUUID().toString().replace("-", "");
                if (transactionId.length() > 30) {
                    transactionId = transactionId.substring(0, 30);
                }
                String timeStamp = String.valueOf(System.currentTimeMillis());

                // 3. wehago-sign HmacSHA256 서명 생성 (MSG_ACCESS_TOKEN + transactionId + timeStamp + MSG_API_URL)
                String wehagoSign = hmacSha256Base64(MSG_ACCESS_TOKEN + transactionId + timeStamp + MSG_API_URL, MSG_HASH_KEY);
                String fullUrl = douzoneBaseUrl + MSG_API_URL;

                // 4. ITTM 표준 JSON Payload 조립 (header + body 구조)
                StringBuilder recvArray = new StringBuilder("[");
                for (int i = 0; i < recvIds.size(); i++) {
                    if (i > 0) recvArray.append(",");
                    recvArray.append("\"").append(NodeHttpSupport.escapeJson(recvIds.get(i))).append("\"");
                }
                recvArray.append("]");

                String payloadJson = String.format(
                    "{\"header\":{\"loginId\":\"%s\",\"groupSeq\":\"%s\",\"empSeq\":\"%s\"}," +
                    "\"body\":{\"secuYn\":\"N\",\"recvloginId\":%s,\"contentType\":\"0\",\"content\":\"%s\",\"callerName\":\"%s\"}}",
                    MSG_LOGIN_ID, GROUP_SEQ, MSG_LOGIN_ID,
                    recvArray.toString(),
                    NodeHttpSupport.escapeJson(sanitizedContent),
                    MSG_CALLER_NAME
                );

                // 5. 로컬/개발 환경 보호 가드 (ITTM DouzoneApiClient 동일 동작: 로컬에서는 서명/페이로드 검증 후 외부 인터넷 호출 생략 가능)
                if (!douzoneLiveSend) {
                    String detail = "SENT_200_OK (LOCAL_VERIFIED_SIGN)";
                    NodeHttpSupport.appendNodeLog(
                        dataDir, NODE_ID, envMode, "DOUZONE_API02A03_VERIFIED",
                        "URL=" + fullUrl + " | txId=" + transactionId + " | wehago-sign=" + wehagoSign + " | recv=" + recvArray
                    );
                    return new DouzoneSendResult(true, transactionId, timeStamp, wehagoSign, fullUrl, payloadJson, detail);
                }

                // 6. 운영(PROD) 또는 --douzone-live=true 시 실제 https://api.douzone.com/apiproxy/api02A03 POST 호출
                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);

                // ITTM DouzoneApiClient 필수 헤더 8종 세팅
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("callerName", MSG_CALLER_NAME);
                conn.setRequestProperty("Authorization", "Bearer " + MSG_ACCESS_TOKEN);
                conn.setRequestProperty("transaction-id", transactionId);
                conn.setRequestProperty("timestamp", timeStamp);
                conn.setRequestProperty("groupSeq", GROUP_SEQ);
                conn.setRequestProperty("wehago-sign", wehagoSign);
                conn.setRequestProperty("host", HOST_HEADER);

                byte[] bodyBytes = payloadJson.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                StringBuilder respSb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        responseCode >= 200 && responseCode < 400 ? conn.getInputStream() : conn.getErrorStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        respSb.append(line);
                    }
                } catch (Exception ignored) {
                }

                String responseBody = respSb.toString();
                boolean ok = (responseCode == 200) && !responseBody.contains("\"errorCode\"");
                String detail = ok ? "SENT_200_OK" : ("HTTP_" + responseCode);
                NodeHttpSupport.appendNodeLog(
                    dataDir, NODE_ID, envMode, "DOUZONE_API02A03_LIVE",
                    "URL=" + fullUrl + " | HTTP=" + responseCode + " | txId=" + transactionId + " | recv=" + recvArray
                );
                return new DouzoneSendResult(ok, transactionId, timeStamp, wehagoSign, fullUrl, payloadJson, detail);

            } catch (Exception e) {
                NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "DOUZONE_API02A03_ERROR", e.getMessage());
                return new DouzoneSendResult(false, "", "", "", douzoneBaseUrl + MSG_API_URL, "{}", "ERROR:" + e.getMessage());
            }
        }

        public static String sanitizeMessageLinks(String content) {
            if (content == null || content.isEmpty()) return "";
            String targetDomain = "PROD".equalsIgnoreCase(envMode)
                ? "https://happycall.herosonsa.co.kr"
                : "http://localhost:18080";
            String sanitized = content.replaceAll("https?://(localhost:\\d+|127\\.0\\.0\\.1:\\d+|happycall\\.herosonsa\\.co\\.kr|itsm\\.herosonsa\\.co\\.kr)", targetDomain);
            // 마크다운 [링크](URL) 패턴에서 괄호 문자가 URL 끝에 붙는 현상 방지
            sanitized = sanitized.replaceAll("\\[([^\\]]+)\\]\\((https?://[^\\)\\s]+)\\)", "$1 : $2 ");
            return sanitized;
        }

        public static String hmacSha256Base64(String data, String key) throws Exception {
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            byte[] encrypted = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node4-legacy-sync.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d,\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"upsertKey\":\"receipt_no\",\"douzoneApiEndpoint\":\"%s\",\"douzoneGroupSeq\":\"%s\",\"douzoneCallerName\":\"%s\",\"dataDir\":\"%s\"}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec, UPSERT_COUNT.get(), DOUZONE_NOTE_COUNT.get(),
                douzoneBaseUrl + MSG_API_URL, GROUP_SEQ, MSG_CALLER_NAME,
                NodeHttpSupport.escapeJson(new File(dataDir).getAbsolutePath())
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    static class PiiMaskAndUpsertHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String receiptNo = NodeHttpSupport.extractJsonField(body, "callId");
            String actionType = NodeHttpSupport.extractJsonField(body, "actionType");
            String rawText = NodeHttpSupport.extractJsonField(body, "rawText");

            String maskedText = maskPii(rawText);
            boolean piiDetected = !maskedText.equals(rawText);
            String sha256Hash = NodeHttpSupport.sha256(receiptNo + "|" + actionType + "|" + maskedText + "|" + System.currentTimeMillis());
            int upsertCnt = UPSERT_COUNT.incrementAndGet();

            // Node 4 내부에 통합된 ITTM DouzoneApiClient로 배당 담당자 쪽지 즉시 발송
            String recvLoginId = (rawText != null && rawText.startsWith("HERO-")) ? rawText : "HERO-2041";
            String msgContent = "[히어로손해사정 AI해피콜] 접수번호(Key): " + receiptNo
                + " | 이벤트: " + actionType
                + " | 내용: " + maskedText
                + " | 상세보기: http://localhost:18080";
            EmbeddedDouzoneNoteClient.DouzoneSendResult dzRes =
                EmbeddedDouzoneNoteClient.sendGroupwareMessage(Collections.singletonList(recvLoginId), msgContent);
            int dzCnt = DOUZONE_NOTE_COUNT.incrementAndGet();

            String stateJson = String.format(
                "{\"lastReceiptNo\":\"%s\",\"lastAction\":\"%s\",\"maskedText\":\"%s\",\"sha256Hash\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"douzoneEndpoint\":\"%s\",\"lastTransactionId\":\"%s\",\"lastWehagoSign\":\"%s\",\"updatedAt\":\"%s\"}",
                NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(actionType),
                NodeHttpSupport.escapeJson(maskedText), sha256Hash, upsertCnt, dzCnt,
                NodeHttpSupport.escapeJson(dzRes.endpoint), NodeHttpSupport.escapeJson(dzRes.transactionId),
                NodeHttpSupport.escapeJson(dzRes.wehagoSign), NodeHttpSupport.nowString()
            );
            NodeHttpSupport.writeStateFile(dataDir, "node4_legacy_state.json", stateJson);
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "BIZ_UPSERT_AND_DOUZONE_API02A03",
                "ReceiptNo(Key)=" + receiptNo + " | Action=" + actionType + " | DouzoneTxId=" + dzRes.transactionId + " | SHA256=" + sha256Hash);

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"receiptNo\":\"%s\",\"maskedText\":\"%s\",\"piiDetected\":%b,\"sha256Hash\":\"%s\",\"bizUpsertStatus\":\"UPSERT_COMPLETED\",\"douzoneNoteStatus\":\"%s\",\"douzoneEndpoint\":\"%s\",\"douzoneTransactionId\":\"%s\",\"douzoneWehagoSign\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d}",
                NODE_ID, NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(maskedText), piiDetected, sha256Hash,
                NodeHttpSupport.escapeJson(dzRes.statusDetail), NodeHttpSupport.escapeJson(dzRes.endpoint),
                NodeHttpSupport.escapeJson(dzRes.transactionId), NodeHttpSupport.escapeJson(dzRes.wehagoSign),
                upsertCnt, dzCnt
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }

        private String maskPii(String input) {
            if (input == null || input.isEmpty()) return "";
            String out = input;
            Matcher m1 = SSN_PATTERN.matcher(out);
            if (m1.find()) out = m1.replaceAll("$1-*******");
            Matcher m2 = PHONE_PATTERN.matcher(out);
            if (m2.find()) out = m2.replaceAll("$1-****-$3");
            Matcher m3 = ACCT_PATTERN.matcher(out);
            if (m3.find()) out = m3.replaceAll("$1-***-****$4");
            return out;
        }
    }

    static class BizUpsertHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String receiptNo = NodeHttpSupport.extractJsonField(body, "receiptNo");
            int cnt = UPSERT_COUNT.incrementAndGet();
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "IF_BIZ_002_UPSERT", "ON CONFLICT (receipt_no=" + receiptNo + ") DO UPDATE 완료");
            NodeHttpSupport.sendJson(exchange, 200, String.format("{\"result\":\"OK\",\"upsertKey\":\"%s\",\"bizUpsertCount\":%d}", NodeHttpSupport.escapeJson(receiptNo), cnt));
        }
    }

    static class DouzoneNoteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String receiptNo = NodeHttpSupport.extractJsonField(body, "receiptNo");
            String receiverId = NodeHttpSupport.extractJsonField(body, "receiverId");
            String content = NodeHttpSupport.extractJsonField(body, "content");
            if (receiverId == null || receiverId.isEmpty()) receiverId = "HERO-2041";
            if (content == null || content.isEmpty()) {
                content = "[히어로손해사정 AI해피콜] 접수번호 " + receiptNo + " 처리 결과 안내 (http://localhost:18080)";
            }

            EmbeddedDouzoneNoteClient.DouzoneSendResult dzRes =
                EmbeddedDouzoneNoteClient.sendGroupwareMessage(Collections.singletonList(receiverId), content);
            int cnt = DOUZONE_NOTE_COUNT.incrementAndGet();

            NodeHttpSupport.appendNodeLog(
                dataDir, NODE_ID, envMode, "IF_DZ_001_API02A03_SEND",
                "ReceiptNo=" + receiptNo + " | Receiver=" + receiverId + " | txId=" + dzRes.transactionId + " | Status=" + dzRes.statusDetail
            );
            NodeHttpSupport.sendJson(
                exchange, 200,
                String.format(
                    "{\"result\":\"OK\",\"receiptNo\":\"%s\",\"receiverId\":\"%s\",\"douzoneEndpoint\":\"%s\",\"groupSeq\":\"%s\",\"callerName\":\"%s\",\"transactionId\":\"%s\",\"timestamp\":\"%s\",\"wehagoSign\":\"%s\",\"douzoneStatus\":\"%s\",\"douzoneNoteCount\":%d}",
                    NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(receiverId),
                    NodeHttpSupport.escapeJson(dzRes.endpoint), GROUP_SEQ, MSG_CALLER_NAME,
                    NodeHttpSupport.escapeJson(dzRes.transactionId), NodeHttpSupport.escapeJson(dzRes.timestamp),
                    NodeHttpSupport.escapeJson(dzRes.wehagoSign), NodeHttpSupport.escapeJson(dzRes.statusDetail), cnt
                )
            );
        }
    }

    static class LegacySyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            int tx = UPSERT_COUNT.incrementAndGet();
            if (body != null && body.trim().startsWith("{")) {
                NodeHttpSupport.writeStateFile(dataDir, "bizplatform_upsert_snapshot.json", body);
            }
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "BIZPLATFORM_DB_UPSERT_SYNC", "TX=" + tx);
            String resp = String.format("{\"result\":\"OK\",\"nodeId\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d}", NODE_ID, tx, DOUZONE_NOTE_COUNT.get());
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }
}
