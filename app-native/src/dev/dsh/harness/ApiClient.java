package dev.dsh.harness;

import org.json.JSONObject;

import dev.dsh.harness.DshLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Native HTTP client for /api/mobile/v1. Cookie auth, no third-party deps. */
public final class ApiClient {
    private final String baseUrl;
    private String cookie;

    public ApiClient(String baseUrl, String cookie) {
        this.baseUrl = baseUrl;
        this.cookie = cookie == null ? "" : cookie;
    }

    public String cookie() {
        return cookie;
    }

    /** Redeem a pairing token (or probe an existing cookie) and keep the cookie. */
    public boolean pair(String token) throws IOException {
        String suffix = token == null || token.isEmpty()
                ? "/"
                : "/?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + suffix).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "text/html");
        try {
            int code = connection.getResponseCode();
            android.util.Log.i("DshNative", "EVENT pair.http status=" + code);
            String setCookie = connection.getHeaderField("Set-Cookie");
            if (setCookie != null && !setCookie.isEmpty()) {
                cookie = setCookie.split(";", 2)[0];
            }
            return code == 200 || code == 302 || code == 303;
        } finally {
            connection.disconnect();
        }
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return sendBody("POST", path, body);
    }

    /** PATCH — the contract §5 session rename (task-5). */
    public JSONObject patch(String path, JSONObject body) throws Exception {
        return sendBody("PATCH", path, body);
    }

    private JSONObject sendBody(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Origin", baseUrl);
        connection.setRequestProperty("X-DSH-Client", "dsh-native/0.1");
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = read(stream);
            // 2xx, not just 200: POST /sessions returns 201 Created (contract §5).
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + text);
            return new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    public JSONObject send(String sessionId, String text, String requestId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("sessionId", sessionId);
        body.put("text", text);
        body.put("requestId", requestId);
        body.put("mode", "queue");
        return post("/api/mobile/v1/send", body);
    }

    /** POST /sessions {cwd, agentPreset?} — 201 {session} (contract §5; preset omitted = default). */
    public JSONObject createSession(String cwd, String agentPreset) throws Exception {
        JSONObject body = new JSONObject();
        if (cwd != null && !cwd.isEmpty()) body.put("cwd", cwd);
        if (agentPreset != null && !agentPreset.isEmpty()) body.put("agentPreset", agentPreset);
        return post("/api/mobile/v1/sessions", body);
    }

    /** PATCH /sessions/{id} {title} — rename (contract §5); 422 session-title-invalid on bad title. */
    public JSONObject renameSession(String sessionId, String title) throws Exception {
        JSONObject body = new JSONObject();
        body.put("title", title);
        return patch("/api/mobile/v1/sessions/"
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name()), body);
    }

    /** GET /presets (v1ag) — 404 on hosts without it; caller degrades to the preset-less sheet. */
    public JSONObject presets() throws Exception {
        return get("/api/mobile/v1/presets");
    }

    /** POST /decisions/{id}/answer — payload shape depends on kind (contract §8.1 / PO v2.1). */
    public JSONObject answerDecision(String decisionId, JSONObject payload) throws Exception {
        return post("/api/mobile/v1/decisions/"
                + URLEncoder.encode(decisionId, StandardCharsets.UTF_8.name()) + "/answer", payload);
    }

    public JSONObject setPluginEnabled(String entryId, boolean enabled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("entryId", entryId);
        body.put("enabled", enabled);
        return post("/api/mobile/v1/plugins/enabled", body);
    }

    /** Contract path: GET /jobs/<jobId>/output?from=<offset> (no query jobId/owner; v1v 200). */
    public JSONObject jobOutput(String jobId, String ownerSessionId) throws Exception {
        return get("/api/mobile/v1/jobs/"
                + URLEncoder.encode(jobId, StandardCharsets.UTF_8.name())
                + "/output?from=0");
    }

    /** Contract path: POST /jobs/<jobId>/kill with optional {reason}. */
    public JSONObject killJob(String jobId, String ownerSessionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("reason", "cancelled by the user");
        return post("/api/mobile/v1/jobs/"
                + URLEncoder.encode(jobId, StandardCharsets.UTF_8.name()) + "/kill", body);
    }

    public JSONObject get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Origin", baseUrl);
        connection.setRequestProperty("X-DSH-Client", "dsh-native/0.1");
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = read(stream);
            if (code != 200) {
                if (code == 401) DshLog.authRejected(401, path);
                if (code == 403) DshLog.authRejected(403, path);
                throw new IOException("HTTP " + code + ": " + body);
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    public JSONObject bootstrap() throws Exception {
        return get("/api/mobile/v1/bootstrap");
    }

    public JSONObject sessions() throws Exception {
        return get("/api/mobile/v1/sessions");
    }

    public JSONObject messages(String sessionId, int limit) throws Exception {
        return messages(sessionId, 0L, limit);
    }

    /** Older-page load: before=<seq> cursor (contract §3.2; 0 = newest page). */
    public JSONObject messages(String sessionId, long beforeSeq, int limit) throws Exception {
        String path = "/api/mobile/v1/messages?sessionId="
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name())
                + "&limit=" + limit;
        if (beforeSeq > 0) path += "&before=" + beforeSeq;
        return get(path);
    }

    public JSONObject plugins() throws Exception {
        return get("/api/mobile/v1/plugins");
    }

    public JSONObject agents() throws Exception {
        return get("/api/mobile/v1/agents");
    }

    public JSONObject jobs() throws Exception {
        return get("/api/mobile/v1/jobs");
    }

    public JSONObject decisions() throws Exception {
        return get("/api/mobile/v1/decisions");
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
