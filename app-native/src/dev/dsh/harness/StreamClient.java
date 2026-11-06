package dev.dsh.harness;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal SSE client over HttpURLConnection (zero deps, no EventSource).
 * Contract §4: GET /api/mobile/v1/stream?since=<lastId>, frames id/event/data,
 * hello / ping {t} / resync / follow frames. One instance = one connection.
 */
public final class StreamClient {
    public interface Listener {
        void onOpen();

        void onHello(String streamId, int replayFrom, int heartbeatMs);

        void onFrame(int id, String event, String data);

        void onResync(String reason);

        void onDisconnect(String reason);

        void onAuth(int status);
    }

    private final Listener listener;
    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    private Thread thread;

    public StreamClient(Listener listener) {
        this.listener = listener;
    }

    public void start(final String baseUrl, final String cookie, final long since) {
        stopped = false;
        thread = new Thread(() -> run(baseUrl, cookie, since), "dsh-sse");
        thread.setDaemon(true);
        thread.start();
    }

    public void close() {
        stopped = true;
        HttpURLConnection current = connection;
        if (current != null) {
            try {
                current.disconnect();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private void run(String baseUrl, String cookie, long since) {
        HttpURLConnection http = null;
        try {
            String url = baseUrl + "/api/mobile/v1/stream?since=" + since;
            http = (HttpURLConnection) new URL(url).openConnection();
            connection = http;
            http.setConnectTimeout(8000);
            // heartbeat is 15s; 40s read timeout = two missed heartbeats before the watchdog.
            http.setReadTimeout(40000);
            http.setRequestProperty("Accept", "text/event-stream");
            http.setRequestProperty("Cache-Control", "no-cache");
            http.setRequestProperty("Origin", baseUrl);
            http.setRequestProperty("X-DSH-Client", "dsh-native/0.1");
            if (cookie != null && !cookie.isEmpty()) {
                http.setRequestProperty("Cookie", cookie);
            }
            int code = http.getResponseCode();
            if (code == 401 || code == 403) {
                if (!stopped) listener.onAuth(code);
                return;
            }
            if (code != 200) {
                if (!stopped) listener.onDisconnect("http-" + code);
                return;
            }
            if (!stopped) listener.onOpen();
            InputStream in = http.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            int id = -1;
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while (!stopped && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null) {
                        dispatch(id, event, data.toString());
                    }
                    id = -1;
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("id:")) {
                    id = parseInt(line.substring(3).trim(), -1);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (!stopped) listener.onDisconnect("eof");
        } catch (Exception error) {
            if (!stopped) listener.onDisconnect("error");
        } finally {
            connection = null;
            if (http != null) {
                try {
                    http.disconnect();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private void dispatch(int id, String event, String data) {
        if ("hello".equals(event)) {
            String streamId = "";
            int replayFrom = 0;
            int heartbeatMs = 15000;
            try {
                org.json.JSONObject json = new org.json.JSONObject(data);
                streamId = json.optString("streamId", "");
                replayFrom = json.optInt("replayFrom", 0);
                heartbeatMs = json.optInt("heartbeatMs", 15000);
            } catch (Exception ignored) {
                // malformed hello still opens the stream
            }
            listener.onHello(streamId, replayFrom, heartbeatMs);
        } else if ("resync".equals(event)) {
            String reason = "gap";
            try {
                reason = new org.json.JSONObject(data).optString("reason", "gap");
            } catch (Exception ignored) {
                // default reason
            }
            listener.onResync(reason);
        }
        listener.onFrame(id, event, data);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception error) {
            return fallback;
        }
    }
}
