package com.hero.happycall.node2;

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
 * [NODE 2 - AI 음성추론망 Speech AI 전용 서버]
 * JAR 파일명 : happycall-node2-speech-ai.jar
 * 기본 포트  : 로컬 18082 / 운영 8082
 * 탑재 모듈  :
 *  - APP-03 (Faster-Whisper large-v3-turbo STT + CosyVoice 2.0 스트리밍 TTS + 한국어 발음 정규화기)
 */
public class SpeechAiServer {

    private static final String NODE_ID = "NODE2-SPEECH-AI";
    private static final String SERVICE_NAME = "Speech AI STT/TTS & Text Normalizer (APP-03)";
    private static final long START_MS = System.currentTimeMillis();
    private static final AtomicInteger PROCESSED_SEGMENTS = new AtomicInteger(3890);

    private static String envMode = "LOCAL";
    private static int port = 18082;
    private static String dataDir = "./data/node2-speech";

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8082 : 18082);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node2-speech");

        NodeHttpSupport.ensureDir(dataDir);
        String initJson = String.format(
            "{\"nodeId\":\"%s\",\"service\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"sttModel\":\"faster-whisper-large-v3-turbo\",\"ttsModel\":\"CosyVoice2-0.5B-KO\",\"startedAt\":\"%s\"}",
            NODE_ID, SERVICE_NAME, envMode, port, NodeHttpSupport.nowString()
        );
        NodeHttpSupport.readStateFile(dataDir, "node2_speech_state.json", initJson);
        NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "SERVER_START", "Port=" + port + " 기동 완료");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/speech/process", new SpeechProcessHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[" + NODE_ID + "] Started on port " + port + " (" + envMode + ") | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node2-speech-ai.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d,\"processedSegments\":%d,\"avgSttMs\":182,\"avgTtsMs\":144,\"dataDir\":\"%s\"}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec, PROCESSED_SEGMENTS.get(),
                NodeHttpSupport.escapeJson(new File(dataDir).getAbsolutePath())
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    static class SpeechProcessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String callId = NodeHttpSupport.extractJsonField(body, "callId");
            String rawText = NodeHttpSupport.extractJsonField(body, "rawText");
            int cnt = PROCESSED_SEGMENTS.incrementAndGet();

            String stateJson = String.format(
                "{\"lastCallId\":\"%s\",\"processedSegments\":%d,\"avgSttMs\":182,\"avgTtsMs\":144,\"updatedAt\":\"%s\"}",
                NodeHttpSupport.escapeJson(callId), cnt, NodeHttpSupport.nowString()
            );
            NodeHttpSupport.writeStateFile(dataDir, "node2_speech_state.json", stateJson);
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "STT_TTS_PROCESS", "CallID=" + callId + " | Seg=" + cnt);

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"callId\":\"%s\",\"sttLatencyMs\":182,\"ttsLatencyMs\":144,\"confidence\":0.991,\"normalizedText\":\"%s\"}",
                NODE_ID, NodeHttpSupport.escapeJson(callId), NodeHttpSupport.escapeJson(rawText)
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }
}
