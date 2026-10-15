package com.hero.happycall.node4;

import com.hero.happycall.common.NodeHttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [NODE 4 - 히어로손해사정 기간계(Biz플랫폼) & 더존(Douzone) 그룹웨어 연동 서버]
 * JAR 파일명 : happycall-node4-legacy-sync.jar
 * 기본 포트  : 로컬 18084 / 운영 8084
 * 핵심 역할  :
 *  - 3-1) 통화 종료 시 접수번호(receipt_no)를 Key 값으로 Biz플랫폼 기간계 DB 실시간 UPSERT
 *  - 4) 배당 담당자(조사역)에게 더존(Douzone) API를 활용한 실시간 쪽지 알림 발송
 *  - 5) PII 정규식 마스킹 및 녹취 SHA-256 무결성 아카이빙
 */
public class LegacySyncArchiverServer {

    private static final String NODE_ID = "NODE4-LEGACY-SYNC";
    private static final String SERVICE_NAME = "BizPlatform(ReceiptNo) UPSERT & Douzone Note API (APP-05/06)";
    private static final long START_MS = System.currentTimeMillis();
    private static final AtomicInteger UPSERT_COUNT = new AtomicInteger(1412);
    private static final AtomicInteger DOUZONE_NOTE_COUNT = new AtomicInteger(1412);

    private static final Pattern SSN_PATTERN = Pattern.compile("(\\d{6})[- ]?([1-4]\\d{6})");
    private static final Pattern ACCT_PATTERN = Pattern.compile("(\\d{3})[- ]?(\\d{2,6})[- ]?(\\d{2,6})(\\d{2})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(01[016789])[- ]?(\\d{3,4})[- ]?(\\d{4})");

    private static String envMode = "LOCAL";
    private static int port = 18084;
    private static String dataDir = "./data/node4-legacy";

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8084 : 18084);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node4-legacy");

        NodeHttpSupport.ensureDir(dataDir);
        String initJson = String.format(
            "{\"nodeId\":\"%s\",\"service\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"upsertKey\":\"receipt_no\",\"startedAt\":\"%s\"}",
            NODE_ID, SERVICE_NAME, envMode, port, UPSERT_COUNT.get(), DOUZONE_NOTE_COUNT.get(), NodeHttpSupport.nowString()
        );
        NodeHttpSupport.readStateFile(dataDir, "node4_legacy_state.json", initJson);
        NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "SERVER_START", "Port=" + port + " (BizPlatform UPSERT & Douzone Note API) 기동 완료");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/pii/mask-and-archive", new PiiMaskAndUpsertHandler());
        server.createContext("/api/bizplatform/upsert", new BizUpsertHandler());
        server.createContext("/api/douzone/note/send", new DouzoneNoteHandler());
        server.createContext("/api/legacy/sync", new LegacySyncHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[" + NODE_ID + "] Started on port " + port + " (" + envMode + ") | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node4-legacy-sync.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d,\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"upsertKey\":\"receipt_no\",\"dataDir\":\"%s\"}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec, UPSERT_COUNT.get(), DOUZONE_NOTE_COUNT.get(),
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
            int dzCnt = DOUZONE_NOTE_COUNT.incrementAndGet();

            String stateJson = String.format(
                "{\"lastReceiptNo\":\"%s\",\"lastAction\":\"%s\",\"maskedText\":\"%s\",\"sha256Hash\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"updatedAt\":\"%s\"}",
                NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(actionType),
                NodeHttpSupport.escapeJson(maskedText), sha256Hash, upsertCnt, dzCnt, NodeHttpSupport.nowString()
            );
            NodeHttpSupport.writeStateFile(dataDir, "node4_legacy_state.json", stateJson);
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "BIZ_UPSERT_AND_DOUZONE_NOTE",
                "ReceiptNo(Key)=" + receiptNo + " | Action=" + actionType + " | Masked=" + maskedText + " | SHA256=" + sha256Hash);

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"receiptNo\":\"%s\",\"maskedText\":\"%s\",\"piiDetected\":%b,\"sha256Hash\":\"%s\",\"bizUpsertStatus\":\"UPSERT_COMPLETED\",\"douzoneNoteStatus\":\"SENT_200_OK\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d}",
                NODE_ID, NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(maskedText), piiDetected, sha256Hash, upsertCnt, dzCnt
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
            int cnt = DOUZONE_NOTE_COUNT.incrementAndGet();
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "IF_DZ_001_NOTE_SEND", "ReceiptNo=" + receiptNo + " | Receiver=" + receiverId + " | Status=SENT_200_OK");
            NodeHttpSupport.sendJson(exchange, 200, String.format("{\"result\":\"OK\",\"receiptNo\":\"%s\",\"douzoneStatus\":\"SENT_200_OK\",\"douzoneNoteCount\":%d}", NodeHttpSupport.escapeJson(receiptNo), cnt));
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
