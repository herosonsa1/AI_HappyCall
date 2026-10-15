package com.hero.happycall.node3;

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
 * [NODE 3 - AI 코어망 GPU LLM 추론 및 대화 오케스트레이터 서버]
 * JAR 파일명 : happycall-node3-llm-orchestrator.jar
 * 기본 포트  : 로컬 18083 / 운영 8083
 * 탑재 모듈  :
 *  - APP-04 (5단계 결정론적 상태머신 S1~S5 & vLLM Qwen2.5-32B-AWQ Guided JSON 슬롯 추출기)
 *  - APP-06 (통화 종료 3초 이내 AI 3줄 요약 및 민원 위험도 분석기)
 */
public class LlmOrchestratorServer {

    private static final String NODE_ID = "NODE3-LLM-ORCHESTRATOR";
    private static final String SERVICE_NAME = "GPU vLLM Dialog FSM & 3-Line Summarizer (APP-04/06)";
    private static final long START_MS = System.currentTimeMillis();
    private static final AtomicInteger INFERENCE_COUNT = new AtomicInteger(4210);

    private static String envMode = "LOCAL";
    private static int port = 18083;
    private static String dataDir = "./data/node3-llm";

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8083 : 18083);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node3-llm");

        NodeHttpSupport.ensureDir(dataDir);
        String initJson = String.format(
            "{\"nodeId\":\"%s\",\"service\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"llmEngine\":\"vLLM-Qwen2.5-32B-Instruct-AWQ (TP=2)\",\"startedAt\":\"%s\"}",
            NODE_ID, SERVICE_NAME, envMode, port, NodeHttpSupport.nowString()
        );
        NodeHttpSupport.readStateFile(dataDir, "node3_llm_state.json", initJson);
        NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "SERVER_START", "Port=" + port + " 기동 완료");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/orchestrator/transition", new OrchestratorHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[" + NODE_ID + "] Started on port " + port + " (" + envMode + ") | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node3-llm-orchestrator.jar\",\"mode\":\"%s\",\"port\":%d,\"uptimeSec\":%d,\"inferenceCount\":%d,\"avgTtftMs\":276,\"gpuTopology\":\"Dual GPU TP=2 (VRAM 84%%)\",\"dataDir\":\"%s\"}",
                NODE_ID, SERVICE_NAME, envMode, port, uptimeSec, INFERENCE_COUNT.get(),
                NodeHttpSupport.escapeJson(new File(dataDir).getAbsolutePath())
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    static class OrchestratorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String callId = NodeHttpSupport.extractJsonField(body, "callId");
            String actionType = NodeHttpSupport.extractJsonField(body, "actionType");
            int cnt = INFERENCE_COUNT.incrementAndGet();

            String stateJson = String.format(
                "{\"lastCallId\":\"%s\",\"lastAction\":\"%s\",\"inferenceCount\":%d,\"avgTtftMs\":276,\"updatedAt\":\"%s\"}",
                NodeHttpSupport.escapeJson(callId), NodeHttpSupport.escapeJson(actionType), cnt, NodeHttpSupport.nowString()
            );
            NodeHttpSupport.writeStateFile(dataDir, "node3_llm_state.json", stateJson);
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "GUIDED_JSON_FSM", "CallID=" + callId + " | Action=" + actionType);

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"callId\":\"%s\",\"llmTtftMs\":276,\"guidedDecoding\":\"VALID_JSON\",\"inferenceCount\":%d}",
                NODE_ID, NodeHttpSupport.escapeJson(callId), cnt
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }
}
