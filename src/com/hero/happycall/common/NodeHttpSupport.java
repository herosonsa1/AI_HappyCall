package com.hero.happycall.common;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5대 인프라 노드(Node 1 ~ Node 5) 공통 HTTP/파일 영속화/암호화 유틸리티
 * 외부 라이브러리 의존성 없이 표준 JDK 1.8 API만으로 동작합니다.
 */
public class NodeHttpSupport {

    public static String parseArg(String[] args, String prefix, String defaultValue) {
        if (args == null) return defaultValue;
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length()).trim();
            }
        }
        return defaultValue;
    }

    public static int parseIntArg(String[] args, String prefix, int defaultValue) {
        String val = parseArg(args, prefix, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static void ensureDir(String dataDir) {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static void appendNodeLog(String dataDir, String nodeId, String envMode, String eventType, String message) {
        try {
            ensureDir(dataDir);
            File logFile = new File(dataDir, nodeId.toLowerCase() + "_audit.log");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
            String line = String.format("[%s] [%s] [%s] [%s] %s%n", ts, envMode, nodeId, eventType, message);
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    public static void writeStateFile(String dataDir, String fileName, String jsonContent) {
        try {
            ensureDir(dataDir);
            File f = new File(dataDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(jsonContent.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    public static String readStateFile(String dataDir, String fileName, String defaultJson) {
        try {
            File f = new File(dataDir, fileName);
            if (f.exists() && f.length() > 2) {
                try (InputStream fis = new FileInputStream(f)) {
                    return new String(readAllBytes(fis), StandardCharsets.UTF_8);
                }
            } else {
                writeStateFile(dataDir, fileName, defaultJson);
                return defaultJson;
            }
        } catch (Exception e) {
            return defaultJson;
        }
    }

    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    public static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
    }

    public static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static String httpGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream()) {
                    return new String(readAllBytes(is), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static String httpPostJson(String urlStr, String jsonBody, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream()) {
                    return new String(readAllBytes(is), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static String extractJsonField(String json, String key) {
        if (json == null) return "";
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public static String nowString() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        }
    }
}
