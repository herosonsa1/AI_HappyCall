package com.hero.happycall.node1;

import com.hero.happycall.common.NodeHttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [NODE 1 - 음성망/DMZ 인프라 서버]
 * JAR 파일명 : happycall-node1-telephony-gw.jar
 * 기본 포트  : 로컬 18081 / 운영 8081
 * 탑재 모듈  :
 *  - APP-01 (캠페인 스케줄러 & 30채널 아웃바운드 다이얼러)
 *  - APP-02 (Asterisk AudioSocket 브릿지 & RFC 2833 DTMF Dual-Input Resolver)
 */
public class TelephonyGatewayServer {

    private static final String NODE_ID = "NODE1-TELEPHONY-GW";
    private static final String SERVICE_NAME = "IP-PBX & Voice/DTMF Stream Gateway (APP-01/02)";
    private static final long START_MS = System.currentTimeMillis();
    private static final AtomicInteger ACTIVE_CHANNELS = new AtomicInteger(24);
    private static final AtomicInteger TOTAL_DIALED = new AtomicInteger(1420);

    private static String envMode = "LOCAL";
    private static int port = 18081;
    private static String dataDir = "./data/node1-telephony";

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8081 : 18081);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node1-telephony");

        NodeHttpSupport.ensureDir(dataDir);
        String initJson = String.format(
            "{\"nodeId\":\"%s\",\"service\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"activeChannels\":%d,\"maxChannels\":30,\"startedAt\":\"%s\"}",
            NODE_ID, SERVICE_NAME, envMode, port, ACTIVE_CHANNELS.get(), NodeHttpSupport.nowString()
        );
        NodeHttpSupport.readStateFile(dataDir, "node1_telephony_state.json", initJson);
        NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "SERVER_START", "Port=" + port + " 기동 완료");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/dialer/trigger", new DialerTriggerHandler());
        server.createContext("/api/gateway/resolve-input", new DualInputHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[" + NODE_ID + "] Started on port " + port + " (" + envMode + ") | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node1-telephony-gw.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d,\"activeChannels\":%d,\"maxChannels\":30,\"totalDialed\":%d,\"dataDir\":\"%s\"}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec, ACTIVE_CHANNELS.get(), TOTAL_DIALED.get(),
                NodeHttpSupport.escapeJson(new File(dataDir).getAbsolutePath())
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    static class DialerTriggerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String callId = NodeHttpSupport.extractJsonField(body, "callId");
            String custName = NodeHttpSupport.extractJsonField(body, "custName");
            int ch = (TOTAL_DIALED.incrementAndGet() % 30) + 1;
            int active = Math.min(30, ACTIVE_CHANNELS.get());

            String stateJson = String.format(
                "{\"lastCallId\":\"%s\",\"lastCustName\":\"%s\",\"allocatedChannel\":%d,\"activeChannels\":%d,\"totalDialed\":%d,\"updatedAt\":\"%s\"}",
                NodeHttpSupport.escapeJson(callId), NodeHttpSupport.escapeJson(custName), ch, active, TOTAL_DIALED.get(), NodeHttpSupport.nowString()
            );
            NodeHttpSupport.writeStateFile(dataDir, "node1_telephony_state.json", stateJson);
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "IF_HC_001_DIAL", "CallID=" + callId + " | Channel=#" + ch);

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"callId\":\"%s\",\"allocatedChannel\":%d,\"protocol\":\"Asterisk-ARI + AudioSocket(9092)\",\"dtmfMode\":\"RFC2833\"}",
                NODE_ID, NodeHttpSupport.escapeJson(callId), ch
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }

    static class DualInputHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String callId = NodeHttpSupport.extractJsonField(body, "callId");
            String inputMode = NodeHttpSupport.extractJsonField(body, "inputMode");
            String rawText = NodeHttpSupport.extractJsonField(body, "rawText");

            String normalizedIntent = "SLOT_VALUE";
            if ("DTMF_KEYPAD".equalsIgnoreCase(inputMode)) {
                if ("1".equals(rawText.trim())) normalizedIntent = "CONSENT_YES";
                else if ("2".equals(rawText.trim())) normalizedIntent = "CONSENT_NO";
                else if ("0".equals(rawText.trim())) normalizedIntent = "AGENT_TRANSFER";
                else normalizedIntent = "DTMF_DIGITS";
            } else {
                if (rawText.contains("동의") || rawText.contains("네")) normalizedIntent = "CONSENT_YES";
                else if (rawText.contains("거부") || rawText.contains("아니")) normalizedIntent = "CONSENT_NO";
                else if (rawText.contains("상담원") || rawText.contains("끼어들기")) normalizedIntent = "BARGE_IN_ESCALATE";
            }

            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "DUAL_INPUT_RESOLVE",
                "CallID=" + callId + " | Mode=" + inputMode + " | Intent=" + normalizedIntent);

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"callId\":\"%s\",\"resolvedMode\":\"%s\",\"normalizedIntent\":\"%s\",\"bargeInLatencyMs\":118}",
                NODE_ID, NodeHttpSupport.escapeJson(callId), NodeHttpSupport.escapeJson(inputMode), normalizedIntent
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }
}
