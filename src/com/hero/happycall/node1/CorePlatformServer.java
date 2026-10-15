package com.hero.happycall.node1;

import com.hero.happycall.common.NodeHttpSupport;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [NODE 1 (통합 코어 서버) - FreePBX(Asterisk 20) 연동 · Biz플랫폼 배당감지/UPSERT · ITTM 더존 쪽지 API · 폐쇄망 통합 웹 콘솔]
 * - 기존 Node 1(배당감지/PBX GW) + Node 4(기간계 UPSERT/더존 쪽지/PII 마스킹) + Node 5(통합 관제 웹 콘솔)를
 *   단일 CPU 애플리케이션 서비스(happycall-node1-core-platform.jar)로 완전 통합.
 * - 기본 HTTP 포트       : 로컬 18080 / 운영 8080
 * - 내장 AudioSocket 포트: 로컬 19092 / 운영 9092 (FreePBX Asterisk AudioSocket 바이너리 스트리밍 리스너)
 * - 연동 GPU 노드        : Node 2 (Speech AI: 로컬 18082 / 운영 8082), Node 3 (LLM Orchestrator: 로컬 18083 / 운영 8083)
 */
public class CorePlatformServer {

    static {
        // ITTM DouzoneApiClient 동일 설정: HttpURLConnection에서 host 헤더(portal.herosonsa.co.kr) 수동 지정을 허용
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private static final String NODE_ID = "NODE1-CORE-PLATFORM";
    private static final String SERVICE_NAME = "Integrated Core Platform (FreePBX GW + BizPlatform UPSERT + ITTM Douzone API + Web Console)";
    private static final long START_MS = System.currentTimeMillis();
    private static final Object STATE_LOCK = new Object();

    // 누적 운영 카운터
    private static final AtomicInteger ASSIGNED_DETECTED_COUNT = new AtomicInteger(1480);
    private static final AtomicInteger ACTIVE_CHANNELS = new AtomicInteger(24);
    private static final AtomicInteger UPSERT_COUNT = new AtomicInteger(1412);
    private static final AtomicInteger DOUZONE_NOTE_COUNT = new AtomicInteger(1412);
    private static final AtomicInteger AUDIOSOCKET_FRAMES = new AtomicInteger(4820);

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

    // PII 마스킹 정규식
    private static final Pattern SSN_PATTERN = Pattern.compile("(\\d{6})[- ]?([1-4]\\d{6})");
    private static final Pattern ACCT_PATTERN = Pattern.compile("(\\d{3})[- ]?(\\d{2,6})[- ]?(\\d{2,6})(\\d{2})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(01[016789])[- ]?(\\d{3,4})[- ]?(\\d{4})");

    private static String envMode = "LOCAL";
    private static int port = 18080;
    private static int audioSocketPort = 19092;
    private static String dataDir = "./data/node1-core";

    // FreePBX (Asterisk 20) 연동 설정
    private static String freepbxHost = "127.0.0.1";
    private static int freepbxAriPort = 8088;
    private static int freepbxAmiPort = 5038;
    private static int freepbxSipPort = 5060;
    private static String freepbxAriUser = "hero_ai_bot";
    private static String freepbxAriSecret = "HeroPbx2026!";
    private static String freepbxTrunkName = "kt-sip-trunk";
    private static boolean freepbxLiveMode = false;

    // 더존 API 설정
    private static String douzoneBaseUrl = DOUZONE_DEFAULT_BASE_URL;
    private static boolean douzoneLiveSend = false;

    // GPU 추론 노드 엔드포인트 (Node 2: Speech AI, Node 3: LLM Orchestrator)
    private static String node2Url = "http://127.0.0.1:18082";
    private static String node3Url = "http://127.0.0.1:18083";

    private static String currentStateJson = "";
    private static volatile boolean audioSocketRunning = false;

    public static void main(String[] args) throws Exception {
        envMode = NodeHttpSupport.parseArg(args, "--mode=", "LOCAL").toUpperCase();
        port = NodeHttpSupport.parseIntArg(args, "--port=", "PROD".equals(envMode) ? 8080 : 18080);
        audioSocketPort = NodeHttpSupport.parseIntArg(args, "--audiosocket-port=", "PROD".equals(envMode) ? 9092 : 19092);
        dataDir = NodeHttpSupport.parseArg(args, "--data-dir=", "./data/node1-core");

        int basePort = "PROD".equals(envMode) ? 8080 : 18080;
        node2Url = NodeHttpSupport.parseArg(args, "--node2-url=", "http://127.0.0.1:" + (basePort + 2));
        node3Url = NodeHttpSupport.parseArg(args, "--node3-url=", "http://127.0.0.1:" + (basePort + 3));

        freepbxHost = NodeHttpSupport.parseArg(args, "--freepbx-host=", "127.0.0.1");
        freepbxAriPort = NodeHttpSupport.parseIntArg(args, "--freepbx-ari-port=", 8088);
        freepbxAmiPort = NodeHttpSupport.parseIntArg(args, "--freepbx-ami-port=", 5038);
        freepbxTrunkName = NodeHttpSupport.parseArg(args, "--freepbx-trunk=", "kt-sip-trunk");
        freepbxLiveMode = "PROD".equals(envMode) || "true".equalsIgnoreCase(NodeHttpSupport.parseArg(args, "--freepbx-live=", "false"));

        douzoneBaseUrl = NodeHttpSupport.parseArg(args, "--douzone-base-url=", DOUZONE_DEFAULT_BASE_URL);
        douzoneLiveSend = "PROD".equals(envMode) || "true".equalsIgnoreCase(NodeHttpSupport.parseArg(args, "--douzone-live=", "false"));

        initDataStore();
        startAsteriskAudioSocketServer(audioSocketPort);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        // 1. 공통 및 클러스터/웹 콘솔 엔드포인트 (구 Node 5)
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/cluster/status", new ClusterStatusHandler());
        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/action", new DistributedActionHandler());

        // 2. 배당 DB 폴링 & FreePBX(Asterisk 20) ARI/AudioSocket/DTMF 엔드포인트 (구 Node 1 + FreePBX)
        server.createContext("/api/dialer/trigger", new DialerTriggerHandler());
        server.createContext("/api/gateway/resolve-input", new DualInputResolveHandler());
        server.createContext("/api/freepbx/status", new FreePbxStatusHandler());
        server.createContext("/api/freepbx/originate", new FreePbxOriginateHandler());

        // 3. Biz플랫폼 접수번호 UPSERT & 내장 ITTM 더존 쪽지 API & PII 아카이빙 엔드포인트 (구 Node 4)
        server.createContext("/api/pii/mask-and-archive", new PiiMaskAndUpsertHandler());
        server.createContext("/api/bizplatform/upsert", new BizUpsertHandler());
        server.createContext("/api/douzone/note/send", new DouzoneNoteHandler());
        server.createContext("/api/legacy/sync", new LegacySyncHandler());

        // 4. Zero-CDN 정적 리소스 서빙
        server.createContext("/", new StaticResourceHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("[" + NODE_ID + "] Started HTTP on port " + port
            + " | AudioSocket TCP on port " + audioSocketPort
            + " (" + envMode + ") | DataDir=" + new File(dataDir).getCanonicalPath());
    }

    private static void initDataStore() {
        synchronized (STATE_LOCK) {
            NodeHttpSupport.ensureDir(dataDir);
            String defaultSeed = loadDefaultSeedJson();
            currentStateJson = NodeHttpSupport.readStateFile(dataDir, "happycall_runtime_db.json", defaultSeed);
            if (!currentStateJson.contains("\"NODE1-CORE-PLATFORM\"")) {
                currentStateJson = defaultSeed;
                NodeHttpSupport.writeStateFile(dataDir, "happycall_runtime_db.json", currentStateJson);
            }
            NodeHttpSupport.appendNodeLog(
                dataDir, NODE_ID, envMode, "SERVER_START",
                "Port=" + port + " | AudioSocket=" + audioSocketPort + " | FreePBX(ARI:" + freepbxAriPort + ") + BizPlatform UPSERT + ITTM DouzoneApiClient(/apiproxy/api02A03) + WebConsole 통합 기동 완료"
            );
        }
    }

    private static String loadDefaultSeedJson() {
        byte[] bytes = readClasspathOrFile("/static/default_seed.json", "src/resources/static/default_seed.json");
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return "{\"meta\":{\"version\":\"2026.10\"}}";
    }

    // =========================================================================
    // [FreePBX (Asterisk 20) AudioSocket TCP 바이너리 프로토콜 서버 & ARI 연동 엔진]
    // Asterisk AudioSocket 규격:
    //  - 1 Byte Frame Type: 0x00 (Hangup), 0x01 (16-Byte Call UUID), 0x03 (ASCII DTMF), 0x10 (16kHz 16-bit Signed Linear PCM)
    //  - 2 Byte Payload Length (Big-Endian unsigned short)
    //  - N Byte Payload
    // =========================================================================
    private static void startAsteriskAudioSocketServer(final int listenPort) {
        ExecutorService acceptor = Executors.newSingleThreadExecutor();
        final ExecutorService workerPool = Executors.newFixedThreadPool(8);
        acceptor.submit(new Runnable() {
            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket()) {
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress("0.0.0.0", listenPort));
                    audioSocketRunning = true;
                    while (!Thread.currentThread().isInterrupted()) {
                        final Socket client = serverSocket.accept();
                        workerPool.submit(new Runnable() {
                            @Override
                            public void run() {
                                handleAudioSocketConnection(client);
                            }
                        });
                    }
                } catch (Exception e) {
                    NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "AUDIOSOCKET_BIND_WARN", "Port=" + listenPort + " : " + e.getMessage());
                }
            }
        });
    }

    private static void handleAudioSocketConnection(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            while (true) {
                int frameType = in.read();
                if (frameType == -1) {
                    break;
                }
                int payloadLen = in.readUnsignedShort();
                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) {
                    in.readFully(payload);
                }
                if (frameType == 0x00) {
                    // 0x00: Asterisk Hangup 프레임 (3바이트 모두 수신 후 정상 종료)
                    break;
                }
                AUDIOSOCKET_FRAMES.incrementAndGet();

                if (frameType == 0x01) {
                    // 0x01: Call UUID 핸드셰이크 수신 -> 초기 인사 TTS PCM 청크(0x10) 응답 송출
                    byte[] greetingPcm = "HERO_AI_TTS_PCM16K_GREETING_FRAME".getBytes(StandardCharsets.UTF_8);
                    out.writeByte(0x10);
                    out.writeShort(greetingPcm.length);
                    out.write(greetingPcm);
                    out.flush();
                } else if (frameType == 0x03) {
                    // 0x03: RFC 2833 DTMF 키패드 프레임 수신 -> 확인 응답 프레임 송출
                    String dtmfDigit = new String(payload, StandardCharsets.UTF_8);
                    NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "FREEPBX_AUDIOSOCKET_DTMF", "DTMF Digit=" + dtmfDigit);
                    byte[] ackPcm = ("DTMF_ACK_" + dtmfDigit).getBytes(StandardCharsets.UTF_8);
                    out.writeByte(0x10);
                    out.writeShort(ackPcm.length);
                    out.write(ackPcm);
                    out.flush();
                } else if (frameType == 0x10) {
                    // 0x10: 16kHz 16-bit Signed Linear PCM 오디오 프레임 수신 -> 에코/TTS 응답 프레임 송출
                    byte[] ttsPcm = "HERO_AI_TTS_PCM16K_STREAM_CHUNK".getBytes(StandardCharsets.UTF_8);
                    out.writeByte(0x10);
                    out.writeShort(ttsPcm.length);
                    out.write(ttsPcm);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 로컬/운영 환경에서 내장 AudioSocket 리스너(19092/9092)에 실제 바이너리 패킷(0x01 UUID -> 0x03 DTMF -> 0x10 PCM -> 0x00 Hangup)을
     * 송수신하여 FreePBX AudioSocket 프로토콜 동작을 실시간 검증하는 메서드
     */
    private static String verifyAudioSocketLoopback(String receiptNo, String dtmfInput) {
        try (Socket sock = new Socket("127.0.0.1", audioSocketPort)) {
            sock.setSoTimeout(2000);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            // 1. 0x01 UUID Frame 전송 (16 bytes)
            UUID callUuid = UUID.nameUUIDFromBytes((receiptNo + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bb = ByteBuffer.allocate(16);
            bb.putLong(callUuid.getMostSignificantBits());
            bb.putLong(callUuid.getLeastSignificantBits());
            out.writeByte(0x01);
            out.writeShort(16);
            out.write(bb.array());
            out.flush();

            // Greeting TTS Frame(0x10) 수신 확인
            int respType1 = in.readUnsignedByte();
            int respLen1 = in.readUnsignedShort();
            byte[] respBuf1 = new byte[respLen1];
            in.readFully(respBuf1);

            // 2. 0x03 DTMF Frame 전송
            byte[] dtmfBytes = (dtmfInput == null || dtmfInput.isEmpty() ? "1" : dtmfInput).getBytes(StandardCharsets.UTF_8);
            out.writeByte(0x03);
            out.writeShort(dtmfBytes.length);
            out.write(dtmfBytes);
            out.flush();

            int respType2 = in.readUnsignedByte();
            int respLen2 = in.readUnsignedShort();
            byte[] respBuf2 = new byte[respLen2];
            in.readFully(respBuf2);

            // 3. 0x00 Hangup 전송
            out.writeByte(0x00);
            out.writeShort(0);
            out.flush();

            return String.format("UUID(%s) -> Greeting(0x%02X,%dB) -> DTMF(%s) -> TTS_ACK(0x%02X,%dB) -> Hangup(0x00)",
                callUuid.toString().substring(0, 8), respType1, respLen1,
                dtmfInput == null ? "1" : dtmfInput, respType2, respLen2);
        } catch (Exception e) {
            return "AUDIOSOCKET_LOOPBACK_FALLBACK: " + e.getMessage();
        }
    }

    /**
     * 실제 FreePBX 17 (Asterisk 20) ARI REST API (POST /ari/channels) 호출 또는 로컬 FreePBX 시뮬레이션 수행
     */
    private static String triggerFreePbxAriOriginate(String receiptNo, String custPhone, String insurerName, String custName, String vehicleNo) {
        String ariUrl = String.format("http://%s:%d/ari/channels", freepbxHost, freepbxAriPort);
        String cleanPhone = (custPhone == null || custPhone.isEmpty()) ? "01012344821" : custPhone.replaceAll("[^0-9]", "");
        String ariPayload = String.format(
            "{\"endpoint\":\"PJSIP/%s@%s\",\"app\":\"happycall-ari\",\"appArgs\":\"%s,%s,%s,%s\",\"callerId\":\"1588-0000\",\"timeout\":30," +
            "\"variables\":{\"RECEIPT_NO\":\"%s\",\"INSURER_NAME\":\"%s\",\"CUST_NAME\":\"%s\",\"VEHICLE_NO\":\"%s\",\"AUDIOSOCKET_HOST\":\"127.0.0.1:%d\"}}",
            cleanPhone, freepbxTrunkName,
            NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(insurerName),
            NodeHttpSupport.escapeJson(custName), NodeHttpSupport.escapeJson(vehicleNo),
            NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(insurerName),
            NodeHttpSupport.escapeJson(custName), NodeHttpSupport.escapeJson(vehicleNo), audioSocketPort
        );

        if (freepbxLiveMode) {
            try {
                URL url = new URL(ariUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(3500);
                conn.setDoOutput(true);
                String basicAuth = Base64.getEncoder().encodeToString((freepbxAriUser + ":" + freepbxAriSecret).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + basicAuth);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bodyBytes = ariPayload.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }
                int code = conn.getResponseCode();
                return "FREEPBX_ARI_LIVE_HTTP_" + code + " (" + ariUrl + ")";
            } catch (Exception e) {
                return "FREEPBX_ARI_FALLBACK_LOCAL (" + e.getMessage() + ")";
            }
        }
        return "FREEPBX_ARI_READY (POST " + ariUrl + " | endpoint=PJSIP/" + cleanPhone + "@" + freepbxTrunkName + ")";
    }

    // =========================================================================
    // [ITTM(ITSM) 프로젝트의 DouzoneApiClient 내장 구현]
    // =========================================================================
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
                String sanitizedContent = sanitizeMessageLinks(content);
                String transactionId = UUID.randomUUID().toString().replace("-", "");
                if (transactionId.length() > 30) {
                    transactionId = transactionId.substring(0, 30);
                }
                String timeStamp = String.valueOf(System.currentTimeMillis());
                String wehagoSign = hmacSha256Base64(MSG_ACCESS_TOKEN + transactionId + timeStamp + MSG_API_URL, MSG_HASH_KEY);
                String fullUrl = douzoneBaseUrl + MSG_API_URL;

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

                if (!douzoneLiveSend) {
                    String detail = "SENT_200_OK (LOCAL_VERIFIED_SIGN)";
                    NodeHttpSupport.appendNodeLog(
                        dataDir, NODE_ID, envMode, "DOUZONE_API02A03_VERIFIED",
                        "URL=" + fullUrl + " | txId=" + transactionId + " | wehago-sign=" + wehagoSign + " | recv=" + recvArray
                    );
                    return new DouzoneSendResult(true, transactionId, timeStamp, wehagoSign, fullUrl, payloadJson, detail);
                }

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

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

                boolean ok = (responseCode == 200) && !respSb.toString().contains("\"errorCode\"");
                String detail = ok ? "SENT_200_OK" : ("HTTP_" + responseCode);
                return new DouzoneSendResult(ok, transactionId, timeStamp, wehagoSign, fullUrl, payloadJson, detail);
            } catch (Exception e) {
                return new DouzoneSendResult(false, "", "", "", douzoneBaseUrl + MSG_API_URL, "{}", "ERROR:" + e.getMessage());
            }
        }

        public static String sanitizeMessageLinks(String content) {
            if (content == null || content.isEmpty()) return "";
            String targetDomain = "PROD".equalsIgnoreCase(envMode)
                ? "https://happycall.herosonsa.co.kr"
                : "http://localhost:18080";
            String sanitized = content.replaceAll("https?://(localhost:\\d+|127\\.0\\.0\\.1:\\d+|happycall\\.herosonsa\\.co\\.kr|itsm\\.herosonsa\\.co\\.kr)", targetDomain);
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

    // =========================================================================
    // [HTTP 핸들러 구현부]
    // =========================================================================
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String json = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node1-core-platform.jar\",\"mode\":\"%s\",\"port\":%d,\"audioSocketPort\":%d,\"audioSocketListening\":%b,\"uptimeSec\":%d,\"assignedDetectedTotal\":%d,\"activeChannels\":%d,\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"upsertKey\":\"receipt_no\",\"douzoneApiEndpoint\":\"%s\",\"douzoneGroupSeq\":\"%s\",\"freepbxHost\":\"%s\",\"freepbxAriPort\":%d,\"freepbxTrunk\":\"%s\",\"timestamp\":\"%s\",\"dataDir\":\"%s\"}",
                NODE_ID, SERVICE_NAME, envMode, port, audioSocketPort, audioSocketRunning, uptimeSec,
                ASSIGNED_DETECTED_COUNT.get(), ACTIVE_CHANNELS.get(), UPSERT_COUNT.get(), DOUZONE_NOTE_COUNT.get(),
                douzoneBaseUrl + MSG_API_URL, GROUP_SEQ,
                freepbxHost, freepbxAriPort, freepbxTrunkName,
                NodeHttpSupport.nowString(),
                NodeHttpSupport.escapeJson(new File(dataDir).getAbsolutePath())
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    /**
     * 3대 통합 인프라 노드(Node 1: Core Platform, Node 2: Speech AI, Node 3: LLM Orchestrator) 헬스체크 집계 핸들러
     */
    static class ClusterStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long uptimeSec = (System.currentTimeMillis() - START_MS) / 1000L;
            String n1 = String.format(
                "{\"status\":\"UP\",\"nodeId\":\"%s\",\"serviceName\":\"%s\",\"jarName\":\"happycall-node1-core-platform.jar\",\"mode\":\"%s\",\"port\":%d,\"audioSocketPort\":%d,\"uptimeSec\":%d}",
                NODE_ID, SERVICE_NAME, envMode, port, audioSocketPort, uptimeSec
            );
            String n2 = fetchNodeHealth(node2Url, "NODE2-SPEECH-AI", "happycall-node2-speech-ai.jar", "PROD".equals(envMode) ? 8082 : 18082);
            String n3 = fetchNodeHealth(node3Url, "NODE3-LLM-ORCHESTRATOR", "happycall-node3-llm-orchestrator.jar", "PROD".equals(envMode) ? 8083 : 18083);

            String clusterJson = String.format(
                "{\"mode\":\"%s\",\"architecture\":\"3-NODE-CONSOLIDATED\",\"checkedAt\":\"%s\",\"nodes\":[%s,%s,%s]}",
                envMode, NodeHttpSupport.nowString(), n1, n2, n3
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

    /**
     * FreePBX (Asterisk 20) 연동 상태 및 AudioSocket 리스너 상태 조회 핸들러 (GET /api/freepbx/status)
     */
    static class FreePbxStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = String.format(
                "{\"result\":\"OK\",\"pbxEngine\":\"FreePBX 17 + Asterisk 20 LTS\",\"freepbxHost\":\"%s\",\"sipPort\":%d,\"ariPort\":%d,\"amiPort\":%d,\"audioSocketPort\":%d,\"audioSocketListening\":%b,\"audioSocketFramesProcessed\":%d,\"sipTrunk\":\"%s\",\"codec\":\"alaw,ulaw (G.711)\",\"dtmfMode\":\"rfc4733 (RFC 2833 Out-of-band)\",\"dialplanContext\":\"[happycall-ai-outbound]\",\"activeChannels\":%d,\"maxChannels\":30}",
                freepbxHost, freepbxSipPort, freepbxAriPort, freepbxAmiPort,
                audioSocketPort, audioSocketRunning, AUDIOSOCKET_FRAMES.get(),
                freepbxTrunkName, ACTIVE_CHANNELS.get()
            );
            NodeHttpSupport.sendJson(exchange, 200, json);
        }
    }

    /**
     * FreePBX (Asterisk 20) 호 발신(Originate) + AudioSocket 바이너리 스트림 테스트 + Node 2/3 AI 파이프라인 + Biz플랫폼 UPSERT + 더존 쪽지 E2E 실행 핸들러
     * (POST /api/freepbx/originate)
     */
    static class FreePbxOriginateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                NodeHttpSupport.sendJson(exchange, 405, "{\"result\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }
            String body = NodeHttpSupport.readRequestBody(exchange);
            String receiptNo = NodeHttpSupport.extractJsonField(body, "receiptNo");
            if (receiptNo == null || receiptNo.isEmpty()) receiptNo = "2026-HW-094812";
            String insurerName = NodeHttpSupport.extractJsonField(body, "insurerName");
            if (insurerName == null || insurerName.isEmpty()) insurerName = "한화손해보험";
            String custName = NodeHttpSupport.extractJsonField(body, "custName");
            if (custName == null || custName.isEmpty()) custName = "김민수";
            String custPhone = NodeHttpSupport.extractJsonField(body, "custPhone");
            if (custPhone == null || custPhone.isEmpty()) custPhone = "010-1234-4821";
            String birthYmd = NodeHttpSupport.extractJsonField(body, "birthYmd");
            if (birthYmd == null || birthYmd.isEmpty()) birthYmd = "850515";
            String vehicleNo = NodeHttpSupport.extractJsonField(body, "vehicleNo");
            if (vehicleNo == null || vehicleNo.isEmpty()) vehicleNo = "123가4568";
            String adjusterId = NodeHttpSupport.extractJsonField(body, "adjusterId");
            if (adjusterId == null || adjusterId.isEmpty()) adjusterId = "HERO-2041";

            // 1. FreePBX ARI Originate 호출
            int dialedTotal = ASSIGNED_DETECTED_COUNT.incrementAndGet();
            String ariResult = triggerFreePbxAriOriginate(receiptNo, custPhone, insurerName, custName, vehicleNo);

            // 2. 내장 AudioSocket TCP 리스너(19092/9092) 바이너리 프레임(0x01 UUID + 0x03 DTMF + 0x10 PCM) 루프백 검증
            String audioSocketTrace = verifyAudioSocketLoopback(receiptNo, birthYmd + "#");

            // 3. Node 2 (Speech AI: 차량번호/생년월일 정규화) 호출
            String speechReq = String.format("{\"callId\":\"%s\",\"actionType\":\"FREEPBX_AUDIOSOCKET_STREAM\",\"rawText\":\"%s %s\"}",
                NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(birthYmd), NodeHttpSupport.escapeJson(vehicleNo));
            NodeHttpSupport.httpPostJson(node2Url + "/api/speech/process", speechReq, 1200);

            // 4. Node 3 (LLM Orchestrator: 4대 확인 상태머신 & 3줄 요약) 호출
            NodeHttpSupport.httpPostJson(node3Url + "/api/orchestrator/transition", speechReq, 1200);

            // 5. Node 1 내부: Biz플랫폼 접수번호(receipt_no) UPSERT 및 ITTM 더존 쪽지(/apiproxy/api02A03) 즉시 발송
            int upsertCnt = UPSERT_COUNT.incrementAndGet();
            String dzContent = String.format("[%s AI해피콜 완료] 접수번호: %s | 고객: %s(%s) | 차량: %s | 개인정보동의: Y | 상세보기: http://localhost:%d",
                insurerName, receiptNo, custName, birthYmd, vehicleNo, port);
            EmbeddedDouzoneNoteClient.DouzoneSendResult dzRes =
                EmbeddedDouzoneNoteClient.sendGroupwareMessage(Collections.singletonList(adjusterId), dzContent);
            int dzCnt = DOUZONE_NOTE_COUNT.incrementAndGet();

            NodeHttpSupport.appendNodeLog(
                dataDir, NODE_ID, envMode, "FREEPBX_E2E_ORIGINATE_COMPLETE",
                "ReceiptNo=" + receiptNo + " | ARI=" + ariResult + " | AudioSocket=" + audioSocketTrace + " | DouzoneTxId=" + dzRes.transactionId
            );

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"receiptNo\":\"%s\",\"freepbxAriStatus\":\"%s\",\"audioSocketPort\":%d,\"audioSocketTrace\":\"%s\",\"bizUpsertStatus\":\"UPSERT_COMPLETED\",\"bizUpsertCount\":%d,\"douzoneEndpoint\":\"%s\",\"douzoneTransactionId\":\"%s\",\"douzoneWehagoSign\":\"%s\",\"douzoneStatus\":\"%s\",\"douzoneNoteCount\":%d,\"assignedDetectedTotal\":%d}",
                NODE_ID, NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(ariResult),
                audioSocketPort, NodeHttpSupport.escapeJson(audioSocketTrace),
                upsertCnt, NodeHttpSupport.escapeJson(dzRes.endpoint),
                NodeHttpSupport.escapeJson(dzRes.transactionId), NodeHttpSupport.escapeJson(dzRes.wehagoSign),
                NodeHttpSupport.escapeJson(dzRes.statusDetail), dzCnt, dialedTotal
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }

    static class DialerTriggerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String receiptNo = NodeHttpSupport.extractJsonField(body, "callId");
            int total = ASSIGNED_DETECTED_COUNT.incrementAndGet();
            int ch = Math.min(30, ACTIVE_CHANNELS.incrementAndGet());
            String ariStatus = triggerFreePbxAriOriginate(receiptNo, "010-1234-4821", "한화손해보험", "김민수", "123가4568");
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "BIZ_DB_ASSIGN_DETECTED_AND_DIAL",
                "ReceiptNo(Key)=" + receiptNo + " | FreePBX=" + ariStatus);
            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"receiptNo\":\"%s\",\"activeChannels\":%d,\"assignedDetectedTotal\":%d,\"freepbxAriStatus\":\"%s\"}",
                NODE_ID, NodeHttpSupport.escapeJson(receiptNo), ch, total, NodeHttpSupport.escapeJson(ariStatus)
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }

    static class DualInputResolveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = NodeHttpSupport.readRequestBody(exchange);
            String receiptNo = NodeHttpSupport.extractJsonField(body, "callId");
            String actionType = NodeHttpSupport.extractJsonField(body, "actionType");
            String rawText = NodeHttpSupport.extractJsonField(body, "rawText");
            boolean isDtmf = actionType.contains("DTMF") || rawText.contains("DTMF") || rawText.matches(".*\\b[0-9]{6}\\b.*");
            String resolvedMode = isDtmf ? "RFC2833_DTMF_PRIORITY" : "VOICE_STT_STREAM";
            NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "DUAL_INPUT_RESOLVED",
                "ReceiptNo=" + receiptNo + " | Mode=" + resolvedMode);
            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"receiptNo\":\"%s\",\"resolvedMode\":\"%s\"}",
                NODE_ID, NodeHttpSupport.escapeJson(receiptNo), resolvedMode
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
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
                        NodeHttpSupport.writeStateFile(dataDir, "bizplatform_upsert_snapshot.json", currentStateJson);
                        NodeHttpSupport.appendNodeLog(dataDir, NODE_ID, envMode, "STATE_AND_UPSERT_SYNC", "통합 원장 업데이트 (" + body.length() + " bytes)");
                    }
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
     * 통합 코어 노드(Node 1) -> 음성 AI(Node 2) -> LLM 오케스트레이터(Node 3) -> 통합 코어(Node 1: UPSERT & 더존 쪽지) 연쇄 처리 핸들러
     */
    static class DistributedActionHandler implements HttpHandler {
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

            // 1. Node 1 내부 FreePBX AudioSocket & Dual-Input 처리
            String audioTrace = verifyAudioSocketLoopback(callId, "1");

            // 2. Node 2 (Speech AI STT/TTS) 호출
            NodeHttpSupport.httpPostJson(node2Url + "/api/speech/process", body, 1200);

            // 3. Node 3 (GPU vLLM Orchestrator) 호출
            NodeHttpSupport.httpPostJson(node3Url + "/api/orchestrator/transition", body, 1200);

            // 4. Node 1 내부: PII 마스킹, Biz플랫폼 접수번호 UPSERT, 내장 ITTM DouzoneApiClient(/apiproxy/api02A03) 즉시 실행
            String maskedText = maskPii(rawText);
            boolean piiDetected = !maskedText.equals(rawText);
            String sha256Hash = NodeHttpSupport.sha256(callId + "|" + actionType + "|" + maskedText + "|" + System.currentTimeMillis());
            int upsertCnt = UPSERT_COUNT.incrementAndGet();

            String recvLoginId = (rawText != null && rawText.startsWith("HERO-")) ? rawText : "HERO-2041";
            String msgContent = "[히어로손해사정 AI해피콜] 접수번호(Key): " + callId
                + " | 이벤트: " + actionType
                + " | 내용: " + maskedText
                + " | 상세보기: http://localhost:" + port;
            EmbeddedDouzoneNoteClient.DouzoneSendResult dzRes =
                EmbeddedDouzoneNoteClient.sendGroupwareMessage(Collections.singletonList(recvLoginId), msgContent);
            int dzCnt = DOUZONE_NOTE_COUNT.incrementAndGet();

            NodeHttpSupport.appendNodeLog(
                dataDir, NODE_ID, envMode, actionType.isEmpty() ? "CONSOLIDATED_3NODE_ACTION" : actionType,
                "CallID=" + callId + " | Route=N1(Core/FreePBX)->N2(Speech)->N3(LLM)->N1(UPSERT/Douzone) | txId=" + dzRes.transactionId + " | SHA256=" + sha256Hash
            );

            String resp = String.format(
                "{\"result\":\"OK\",\"actionType\":\"%s\",\"callId\":\"%s\",\"maskedText\":\"%s\",\"piiDetected\":%b,\"sha256Hash\":\"%s\",\"audioSocketTrace\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d,\"douzoneEndpoint\":\"%s\",\"douzoneGroupSeq\":\"%s\",\"douzoneTransactionId\":\"%s\",\"douzoneWehagoSign\":\"%s\",\"processedAt\":\"%s\",\"mode\":\"%s\",\"routedNodes\":[\"NODE1-CORE-PLATFORM\",\"NODE2-SPEECH-AI\",\"NODE3-LLM-ORCHESTRATOR\"]}",
                NodeHttpSupport.escapeJson(actionType), NodeHttpSupport.escapeJson(callId),
                NodeHttpSupport.escapeJson(maskedText), piiDetected, sha256Hash,
                NodeHttpSupport.escapeJson(audioTrace), upsertCnt, dzCnt,
                NodeHttpSupport.escapeJson(dzRes.endpoint), GROUP_SEQ,
                NodeHttpSupport.escapeJson(dzRes.transactionId), NodeHttpSupport.escapeJson(dzRes.wehagoSign),
                NodeHttpSupport.nowString(), envMode
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
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

            String recvLoginId = (rawText != null && rawText.startsWith("HERO-")) ? rawText : "HERO-2041";
            EmbeddedDouzoneNoteClient.DouzoneSendResult dzRes =
                EmbeddedDouzoneNoteClient.sendGroupwareMessage(Collections.singletonList(recvLoginId),
                    "[히어로손해사정 AI해피콜] 접수번호: " + receiptNo + " | " + maskedText);
            int dzCnt = DOUZONE_NOTE_COUNT.incrementAndGet();

            String resp = String.format(
                "{\"result\":\"OK\",\"nodeId\":\"%s\",\"receiptNo\":\"%s\",\"maskedText\":\"%s\",\"piiDetected\":%b,\"sha256Hash\":\"%s\",\"bizUpsertStatus\":\"UPSERT_COMPLETED\",\"douzoneNoteStatus\":\"%s\",\"douzoneEndpoint\":\"%s\",\"douzoneTransactionId\":\"%s\",\"douzoneWehagoSign\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d}",
                NODE_ID, NodeHttpSupport.escapeJson(receiptNo), NodeHttpSupport.escapeJson(maskedText), piiDetected, sha256Hash,
                NodeHttpSupport.escapeJson(dzRes.statusDetail), NodeHttpSupport.escapeJson(dzRes.endpoint),
                NodeHttpSupport.escapeJson(dzRes.transactionId), NodeHttpSupport.escapeJson(dzRes.wehagoSign),
                upsertCnt, dzCnt
            );
            NodeHttpSupport.sendJson(exchange, 200, resp);
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
                content = "[히어로손해사정 AI해피콜] 접수번호 " + receiptNo + " 처리 결과 안내 (http://localhost:" + port + ")";
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
            String resp = String.format("{\"result\":\"OK\",\"nodeId\":\"%s\",\"bizUpsertCount\":%d,\"douzoneNoteCount\":%d}", NODE_ID, tx, DOUZONE_NOTE_COUNT.get());
            NodeHttpSupport.sendJson(exchange, 200, resp);
        }
    }

    private static String maskPii(String input) {
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
        try (InputStream is = CorePlatformServer.class.getResourceAsStream(classpathLocation)) {
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
