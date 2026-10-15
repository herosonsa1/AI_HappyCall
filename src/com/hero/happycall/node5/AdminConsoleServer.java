package com.hero.happycall.node5;

import com.hero.happycall.common.NodeHttpSupport;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [NODE 5 - 업무망/관제망 통합 APM 관제 및 차세대 기간계 원장 웹 콘솔 서버]
 * JAR 파일명 : happycall-node5-admin-console.jar
 * 기본 포트  : 로컬 18080 / 운영 8080
 * 탑재 모듈  :
 *  - APP-07 (REA 벤치마킹 Zero-CDN 통합 웹 UI 서빙 및 Node 1~4 마이크로서비스 오케스트레이션 BFF)
 */
public class AdminConsoleServer {

    private static final String NODE_ID = "NODE5-ADMIN-CONSOLE";
    private static final String SERVICE_NAME = "Integrated APM & Legacy Ledger Web Console (APP-07)";
    private static final long START_MS = System.currentTimeMillis();
    private static final Object STATE_LOCK = new Object();

    private static String envMode = "LOCAL";
    private static int port = 18080;
    private static String dataDir = "./data/node5-console";

    // 타 인프라 노드 엔드포인트 (로컬: 18081~18084 / 운영: 8081~8084)
    private static String node1Url = "http://127.0.0.1:18081";
    private static String node2Url = "http://127.0.0.1:18082";
    private static String node3Url = "http://127.0.0.1:18083";
    private static String node4Url = "http://127.0.0.1:18084";

    private static String currentStateJson = "";

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8080 : 18080);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node5-console");

        int baseNodePort = "PROD".equals(envMode) ? 8080 : 18080;
        node1Url = NodeHttpSupport.parseArg(args, "--node1-url=", "http://127.0.0.1:" + (baseNodePort + 1));
        node2Url = NodeHttpSupport.parseArg(args, "--node2-url=", "http://127.0.0.1:" + (baseNodePort + 2));
        node3Url = NodeHttpSupport.parseArg(args, "--node3-url=", "http://127.0.0.1:" + (baseNodePort + 3));
        node4Url = NodeHttpSupport.parseArg(args, "--node4-url=", "http://127.0.0.1:" + (baseNodePort + 4));

        initDataStore();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/cluster/status", new ClusterStatusHandler());
        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/action", new DistributedActionHandler());
        server.createContext("/", new StaticResourceHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("[" + NODE_ID + "] Started on port " + port + " (" + envMode + ") | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    private static void initDataStore() {
        synchronized (STATE_LOCK) {
            NodeHttpSupport.ensureDir(dataDir);
            String defaultSeed = loadDefaultSeedJson();
            currentStateJson = NodeHttpSupport.readStateFile(dataDir, "happycall_runtime_db.json", defaultSeed);
            if (!currentStateJson.contains("\"receiptNo\"")) {
                currentStateJson = defaultSeed;
                NodeHttpSupport.writeStateFile(dataDir, "happycall_runtime_db.json", currentStateJson);
            }
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "SERVER_START", "Port=" + port + " 콘솔 기동 완료");
        }
    }

    private static String loadDefaultSeedJson() {
        byte[] bytes = readClasspathOrFile("/static/default_seed.json", "src/resources/static/default_seed.json");
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return "{\"meta\":{\"version\":\"2026.10\"}}";
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node5-admin-console.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d,\"timestamp\":\"%s\",\"dataDir\":\"%s\",\"reaEvidenceId\":\"ev_cb69f03e63dbb415ecf696b48d2293882eeb53379a840a71b7a2af98d420eabe\"}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec, NodeHttpSupport.nowString(),
                NodeHttpSupport.escapeJson(new File(dataDir).getAbsolutePath())
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    /**
     * 5대 분리 인프라 노드(Node 1 ~ Node 5) 실시간 헬스체크 집계 핸들러 (GET /api/cluster/status)
     */
    static class ClusterStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String n1 = fetchNodeHealth(node1Url, "NODE1-TELEPHONY-GW", "happycall-node1-telephony-gw.jar", "PROD".equals(envMode) ? 8081 : 18081);
            String n2 = fetchNodeHealth(node2Url, "NODE2-SPEECH-AI", "happycall-node2-speech-ai.jar", "PROD".equals(envMode) ? 8082 : 18082);
            String n3 = fetchNodeHealth(node3Url, "NODE3-LLM-ORCHESTRATOR", "happycall-node3-llm-orchestrator.jar", "PROD".equals(envMode) ? 8083 : 18083);
            String n4 = fetchNodeHealth(node4Url, "NODE4-LEGACY-SYNC", "happycall-node4-legacy-sync.jar", "PROD".equals(envMode) ? 8084 : 18084);
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String n5 = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node5-admin-console.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec
            );

            String clusterJson = String.format(
                "{\"mode\":\"%s\",\"checkedAt\":\"%s\",\"nodes\":[%s,%s,%s,%s,%s]}",
                envMode, NodeHttpSupport.nowString(), n1, n2, n3, n4, n5
            );
            NodeHttpSupport.sendJson(exchange, 200, clusterJson);
        }

        private String fetchNodeHealth(String baseUrl, String fallbackId, String jarName, int defaultPort) {
            String res = NodeHttpSupport.httpGet(baseUrl + "/api/health", 1200);
            if (res != null && res.trim().startsWith("{")) {
                return res;
            }
            return String.format(
                "{\"status\":\"STANDBY\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":0}",
                fallbackId, fallbackId, jarName, envMode, defaultPort
            );
        }
    }

    static class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod().toUpperCase();
            if ("GET".equals(method)) {
                synchronized (STATE_LOCK) {
                    NodeHttpSupport.sendJson(exchange, 200, currentStateJson);
                }
            } else if ("POST".equals(method)) {
                String body = NodeHttpSupport.readRequestBody(exchange);
                if (body != null && body.trim().startsWith("{")) {
                    synchronized (STATE_LOCK) {
                        currentStateJson = body;
                        NodeHttpSupport.writeStateFile(dataDir, "happycall_runtime_db.json", currentStateJson);
                        NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "STATE_SYNC", "원장 업데이트 (" + body.length() + " bytes)");
                    }
                    // Node 4 (기간계 동기화 서버)로도 실시간 원장 스냅샷 전파
                    NodeHttpSupport.httpPostJson(node4Url + "/api/legacy/sync", body, 1500);
                    NodeHttpSupport.sendJson(exchange, 200, "{\"result\":\"OK\",\"saved\":true}");
                } else {
                    NodeHttpSupport.sendJson(exchange, 400, "{\"result\":\"ERROR\"}");
                }
            } else {
                NodeHttpSupport.sendJson(exchange, 405, "{\"result\":\"METHOD_NOT_ALLOWED\"}");
            }
        }
    }

    /**
     * 분산 노드(Node 1 -> Node 2 -> Node 3 -> Node 4) 연쇄 호출 오케스트레이션 핸들러 (POST /api/action)
     */
    static class DistributedActionHandler implements HttpHandler {
        private static final Pattern SSN_PATTERN = Pattern.compile("(\\d{6})[- ]?([1-4]\\d{6})");
        private static final Pattern ACCT_PATTERN = Pattern.compile("(\\d{3})[- ]?(\\d{2,6})[- ]?(\\d{2,6})(\\d{2})");
        private static final Pattern PHONE_PATTERN = Pattern.compile("(01[016789])[- ]?(\\d{3,4})[- ]?(\\d{4})");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                NodeHttpSupport.sendJson(exchange, 405, "{\"result\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }
            String body = NodeHttpSupport.readRequestBody(exchange);
            String actionType = NodeHttpSupport.extractJsonField(body, "actionType");
            String rawText = NodeHttpSupport.extractJsonField(body, "rawText");
            String callId = NodeHttpSupport.extractJsonField(body, "callId");

            // 1. Node 1 (Telephony/DTMF GW) 호출
            if (actionType.contains("TRIGGER")) {
                NodeHttpSupport.httpPostJson(node1Url + "/api/dialer/trigger", body, 1200);
            } else {
                NodeHttpSupport.httpPostJson(node1Url + "/api/gateway/resolve-input", body, 1200);
            }

            // 2. Node 2 (Speech AI STT/TTS) 호출
            NodeHttpSupport.httpPostJson(node2Url + "/api/speech/process", body, 1200);

            // 3. Node 3 (GPU vLLM Orchestrator) 호출
            NodeHttpSupport.httpPostJson(node3Url + "/api/orchestrator/transition", body, 1200);

            // 4. Node 4 (PII Masking & Legacy Sync Archiver) 호출
            String node4Res = NodeHttpSupport.httpPostJson(node4Url + "/api/pii/mask-and-archive", body, 1500);

            String maskedText;
            String sha256Hash;
            if (node4Res != null && node4Res.contains("\"maskedText\"")) {
                maskedText = NodeHttpSupport.extractJsonField(node4Res, "maskedText");
                sha256Hash = NodeHttpSupport.extractJsonField(node4Res, "sha256Hash");
            } else {
                maskedText = maskFallback(rawText);
                sha256Hash = NodeHttpSupport.sha256(callId + "|" + actionType + "|" + maskedText + "|" + System.currentTimeMillis());
            }

            boolean piiDetected = !maskedText.equals(rawText);
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, actionType.isEmpty() ? "DISTRIBUTED_RPC" : actionType,
                "CallID=" + callId + " | Route=N1->N2->N3->N4 | SHA256=" + sha256Hash);

            String resp = String.format(
                "{\"result\":\"OK\",\"actionType\":\"%s\",\"callId\":\"%s\",\"maskedText\":\"%s\",\"piiDetected\":%b,\"sha256Hash\":\"%s\",\"processedAt\":\"%s\",\"mode\":\"%s\",\"routedNodes\":[\"NODE1-TELEPHONY-GW\",\"NODE2-SPEECH-AI\",\"NODE3-LLM-ORCHESTRATOR\",\"NODE4-LEGACY-SYNC\",\"NODE5-ADMIN-CONSOLE\"]}",
                NodeHttpSupport.escapeJson(actionType), NodeHttpSupport.escapeJson(callId),
                NodeHttpSupport.escapeJson(maskedText), piiDetected, sha256Hash, NodeHttpSupport.nowString(), envMode
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }

        private String maskFallback(String input) {
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

    static class StaticResourceHandler implements HttpHandler {
        private static final Map<String, String> MIME_TYPES = new HashMap<String, String>();

        static {
            MIME_TYPES.put("html", "text/html; charset=utf-8");
            MIME_TYPES.put("css", "text/css; charset=utf-8");
            MIME_TYPES.put("js", "application/javascript; charset=utf-8");
            MIME_TYPES.put("json", "application/json; charset=utf-8");
            MIME_TYPES.put("svg", "image/svg+xml");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            if (path.contains("..")) {
                NodeHttpSupport.sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }

            byte[] content = readClasspathOrFile("/static" + path, "src/resources/static" + path);
            if (content == null) {
                content = readClasspathOrFile("/static/index.html", "src/resources/static/index.html");
                path = "/index.html";
            }
            if (content == null) {
                NodeHttpSupport.sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
                return;
            }

            String ext = "html";
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) ext = path.substring(dot + 1).toLowerCase();
            String contentType = MIME_TYPES.containsKey(ext) ? MIME_TYPES.get(ext) : "application/octet-stream";

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    private static byte[] readClasspathOrFile(String classpathLocation, String fileFallbackLocation) {
        try (InputStream is = AdminConsoleServer.class.getResourceAsStream(classpathLocation)) {
            if (is != null) {
                return NodeHttpSupport.readAllBytes(is);
            }
        } catch (Exception ignored) {
        }
        File fallback = new File(fileFallbackLocation);
        if (fallback.exists() && fallback.isFile()) {
            try (InputStream fis = new FileInputStream(fallback)) {
                return NodeHttpSupport.readAllBytes(fis);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
