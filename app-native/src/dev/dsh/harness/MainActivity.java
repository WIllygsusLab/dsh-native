package dev.dsh.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Native Discord-shaped shell for DSH: rail, channel list, chat, 5 tabs. */
public final class MainActivity extends Activity {
    private static final String TAG = "DshNative";
    private static final int TAB_CHATS = 0;
    private static final int TAB_AGENTS = 1;
    private static final int TAB_PLUGINS = 2;
    private static final int TAB_JOBS = 3;
    private static final int TAB_YOU = 4;

    private static final int CARD_AGENT = 0;
    private static final int CARD_PLUGIN = 1;
    private static final int CARD_JOB = 2;
    private static final int CARD_DECISION = 3;
    private static final int CARD_SECTION = 4;

    private static final int ROW_SECTION = 0;
    private static final int ROW_CHANNEL = 1;
    private static final int ROW_MESSAGE = 2;
    private static final int ROW_TEST_TOGGLE = 3;

    private Prefs prefs;
    private ApiClient api;
    private String baseUrl;
    private String token;
    private String cookie;
    private Models.Bootstrap data;
    private boolean chatOpen;
    private Models.Session currentSession;
    private int tab = TAB_CHATS;
    private String filterCwd;
    private String query = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<Models.Message> transcript = new ArrayList<>();
    private MessageAdapter transcriptAdapter;
    private ListView transcriptList;
    private TextView transcriptLoadingOlder;
    private boolean loadingOlder;
    private boolean hasOlder;
    private long oldestSeq;
    private TextView chatEmptyView;
    private StreamClient stream;
    private final Handler streamHandler = new Handler(Looper.getMainLooper());
    private boolean foreground;
    private long lastStreamId;
    private int reconnectAttempt;
    private long lastFrameAt;
    private long throughSeq;
    private boolean transcriptLoading;
    private final Map<Long, Models.Message> pendingStreamMessages = new LinkedHashMap<>();
    private final Map<String, Long> throughSeqBySession = new java.util.HashMap<>();
    private final List<Models.Message> pendingSends = new ArrayList<>();
    private EditText composerInput;
    private Button composerSend;
    private List<Models.Decision> decisionsFeed = new ArrayList<>();
    private boolean decisionsAnswerable;
    private String decisionsSourceProblem;
    private int decisionsWaiting;
    private int decisionsUrgent;
    private boolean decisionsLoading;
    private boolean decisionsBadgeRefreshing;
    private Runnable decisionsPollRunnable;
    private String pendingOpenSessionId;
    private ListView channelListView;
    private ChannelAdapter channelAdapter;
    private List<Row> channelRowsLive;
    private boolean pluginRestartRequired;
    private String pendingDecisionId;
    private android.net.Uri pendingDebugUri;
    /** DEBUG-only synthetic POST /sessions failure (task-5 phase 2 error-surface fixture). */
    private String debugCreateFailure;
    /** DEBUG-only synthetic GET /presets roster (task-5 phase 2 broken-row fixture). */
    private String debugPresetRoster;
    private boolean showingDecisions;
    private final java.util.Map<String, String> decisionLocalState = new java.util.HashMap<>();
    private final java.util.Map<String, JSONObject> decisionRetryPayload = new java.util.HashMap<>();
    private final java.util.Set<String> decisionSubmitting = new java.util.HashSet<>();
    private final java.util.Set<String> decisionDismissed = new java.util.HashSet<>();
    private final java.util.Set<String> sendOkEmitted = new java.util.HashSet<>();
    private final java.util.Map<String, java.util.Set<String>> questionSelections = new java.util.HashMap<>();
    private final java.util.Map<String, Runnable> rejectTimers = new java.util.HashMap<>();
    private TextView jumpLatest;
    private int unseenLive;

    private TextView topTitle;
    private TextView topSubtitle;
    private ImageButton backButton;
    private LinearLayout railSpaces;
    private FrameLayout content;
    private TextView[] tabs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DshLog.appStart();
        DshLog.lifecycle("create");
        prefs = new Prefs(this);
        baseUrl = prefs.baseUrl();
        token = prefs.token();
        cookie = prefs.cookie();
        if (baseUrl.isEmpty()) {
            startActivity(new Intent(this, ConnectActivity.class));
            finish();
            return;
        }
        api = new ApiClient(baseUrl, cookie);
        setContentView(R.layout.activity_main);
        topTitle = findViewById(R.id.topbar_title);
        topSubtitle = (TextView) find(R.id.conn_status, R.id.topbar_subtitle);
        backButton = findViewById(R.id.topbar_back);
        railSpaces = findViewById(R.id.rail_spaces);
        View railHost = findViewById(R.id.rail_host);
        if (railHost != null) {
            railHost.setOnLongClickListener(v -> {
                showRailMenu();
                return true;
            });
        }
        // UI overhaul: Home clears the workspace filter; Tools opens the tools drawer.
        View railHome = findViewById(R.id.rail_home);
        if (railHome != null) {
            railHome.setOnClickListener(v -> {
                filterCwd = null;
                showTab(TAB_CHATS);
                hideRailAfterSelect();
            });
        }
        View railTools = findViewById(R.id.rail_tools);
        if (railTools != null) railTools.setOnClickListener(v -> {
            showToolsDrawer();
            hideRailAfterSelect();
        });
        content = (FrameLayout) find(R.id.main_screen, R.id.content);
        tabs = new TextView[]{
                findViewById(R.id.tab_chats),
                findViewById(R.id.tab_agents),
                findViewById(R.id.tab_plugins),
                findViewById(R.id.tab_jobs),
                findViewById(R.id.tab_you),
        };
        backButton.setOnClickListener(v -> goBack());
        View reconnect = find(R.id.reconnect_button, 0);
        if (reconnect != null) reconnect.setOnClickListener(v -> load());
        findViewById(R.id.topbar_action).setOnClickListener(v -> search());
        findViewById(R.id.topbar_action_2).setOnClickListener(v -> {
            if (chatOpen) showMembers();
            else showMenu(v);
        });
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(v -> showTab(index));
            tabs[i].setContentDescription(tabs[i].getText() + ", tab " + (i + 1) + " of 5");
            tabs[i].setSelected(i == 0);
        }
        ImageButton railToggle = findViewById(R.id.rail_toggle);
        if (railToggle != null) railToggle.setOnClickListener(v -> toggleRail());
        applyRailHidden(prefs.railHidden());
        load();
        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        android.net.Uri data = intent.getData();
        if (!"dshnative".equals(data.getScheme())) return;
        if (AppConfig.DEBUG && "debug".equals(data.getHost())) {
            handleDebugUri(data);
            return;
        }
        String last = data.getLastPathSegment();
        if ("decision".equals(data.getHost()) && last != null) {
            pendingDecisionId = last;
            DshLog.decisionOpen(last, "deeplink");
            if (data != null && !baseUrl.isEmpty()) showDecisions();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        DshLog.lifecycle("start");
        startStream();
        scheduleDecisionsPoll();
    }

    @Override
    protected void onStop() {
        foreground = false;
        DshLog.lifecycle("stop");
        stopStream("background");
        stopDecisionsPoll();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopStream("destroy");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- live stream (SSE)

    private void startStream() {
        if (!foreground || stream != null || baseUrl.isEmpty()) return;
        final long since = lastStreamId;
        DshLog.streamConnect((int) since);
        lastFrameAt = System.currentTimeMillis();
        stream = new StreamClient(new StreamClient.Listener() {
            @Override
            public void onOpen() {
            }

            @Override
            public void onHello(String streamId, int replayFrom, int heartbeatMs) {
                runOnUiThread(() -> {
                    reconnectAttempt = 0;
                    lastFrameAt = System.currentTimeMillis();
                    DshLog.streamHello(streamId, replayFrom, heartbeatMs);
                });
            }

            @Override
            public void onFrame(int id, String event, String data) {
                runOnUiThread(() -> handleStreamFrame(id, event, data));
            }

            @Override
            public void onResync(String reason) {
                runOnUiThread(() -> handleResync(reason));
            }

            @Override
            public void onDisconnect(String reason) {
                runOnUiThread(() -> handleStreamDisconnect(reason));
            }

            @Override
            public void onAuth(int status) {
                runOnUiThread(() -> {
                    DshLog.authRejected(status, "/stream");
                    showError(status == 401 ? ERROR_401 : ERROR_403, null);
                });
            }
        });
        stream.start(baseUrl, cookie, since);
        scheduleHeartbeatWatchdog();
    }

    private void scheduleHeartbeatWatchdog() {
        streamHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!foreground) return;
                long idle = System.currentTimeMillis() - lastFrameAt;
                if (stream != null && idle > 30000L) {
                    // R7: two missed heartbeats => treat the connection as dead.
                    stopStream("heartbeat-timeout");
                    scheduleReconnect();
                } else if (stream != null) {
                    scheduleHeartbeatWatchdog();
                }
            }
        }, 15000L);
    }

    private void stopStream(String reason) {
        StreamClient current = stream;
        stream = null;
        if (current != null) {
            current.close();
            DshLog.streamDisconnect(reason);
        }
    }

    private void handleStreamDisconnect(String reason) {
        stream = null;
        DshLog.streamDisconnect(reason);
        if (foreground && !isFinishing()) scheduleReconnect();
    }

    private void scheduleReconnect() {
        reconnectAttempt++;
        long base = 1000L << Math.min(reconnectAttempt - 1, 5);
        if (base > 30000L) base = 30000L;
        long delay = (long) (base * (0.8 + Math.random() * 0.4));
        DshLog.streamReconnect(reconnectAttempt, delay);
        streamHandler.postDelayed(this::startStream, delay);
    }

    private void handleResync(String reason) {
        DshLog.streamResync(reason);
        // New id space / stale cache: drop the cursor, re-bootstrap, refetch the open transcript.
        lastStreamId = 0;
        reconnectAttempt = 0;
        load();
        if (chatOpen && currentSession != null) {
            transcriptLoading = true;
            loadMessages(currentSession, transcriptList, chatEmptyView, 0);
        }
    }

    private void handleStreamFrame(int id, String event, String data) {
        long frameAt = System.currentTimeMillis();
        lastFrameAt = frameAt;
        if (id > 0) {
            if (id <= lastStreamId) return; // R4: apply only id > lastAppliedId
            lastStreamId = id;
        }
        DshLog.streamFrame(id, event);
        switch (event) {
            case "assistant.delta":
                handleAssistantDelta(data);
                break;
            case "assistant.done":
                if (transcriptAdapter != null) transcriptAdapter.setStreamText(null);
                break;
            case "message.append":
                handleMessageAppend(data, frameAt);
                break;
            case "message.replace":
                handleMessageReplace(data);
                break;
            case "session.upsert":
            case "session.status":
                handleSessionUpdate(data, event);
                break;
            case "job.upsert":
            case "decision.upsert":
            case "agent.upsert":
            case "plugin.upsert":
                if ("decision.upsert".equals(event)) handleDecisionUpsert(data);
                if (tab == TAB_JOBS) showTab(TAB_JOBS);
                else if (tab == TAB_AGENTS && "agent.upsert".equals(event)) showTab(TAB_AGENTS);
                else if (tab == TAB_PLUGINS && "plugin.upsert".equals(event)) showTab(TAB_PLUGINS);
                break;
            case "ping":
            default:
                break;
        }
    }

    private void handleMessageAppend(String data, long frameAt) {
        try {
            JSONObject json = new JSONObject(data);
            String sessionId = json.optString("sessionId", "");
            JSONObject messageJson = json.optJSONObject("message");
            if (messageJson == null) return;
            Models.Message message = Models.Message.from(messageJson);
            boolean openSession = currentSession != null && sessionId.equals(currentSession.id);
            if (openSession && "user".equals(message.role)
                    && (message.clientRequestId != null
                        || findPendingByText(message.text) != null)) {
                reconcileDurable(message);
                return;
            }
            long sessionThrough = openSession
                    ? (throughSeqBySession.containsKey(sessionId)
                        ? throughSeqBySession.get(sessionId) : throughSeq) : 0L;
            if (message.seq > 0 && message.seq <= sessionThrough) {
                DshLog.messageDedupe(sessionId, message.seq, "throughSeq");
                return;
            }
            if (openSession) {
                appendLive(message, frameAt);
            } else {
                DshLog.streamDrop(sessionId, "not-open");
            }
            updateSessionPreview(sessionId, message.text);
        } catch (Exception error) {
            Log.w(TAG, "bad message.append frame", error);
        }
    }

    private void updateJumpPill(boolean nearBottom) {
        if (jumpLatest == null) return;
        if (nearBottom) {
            unseenLive = 0;
            jumpLatest.setVisibility(View.GONE);
        } else {
            unseenLive++;
            jumpLatest.setText(getString(R.string.jump_to_latest_fmt, unseenLive));
            jumpLatest.setVisibility(View.VISIBLE);
        }
    }

    private void appendLive(Models.Message message, long frameAt) {
        if (currentSession == null) return;
        if (transcriptLoading) {
            pendingStreamMessages.put(message.seq, message);
            return;
        }
        int existing = -1;
        for (int i = 0; i < transcript.size(); i++) {
            if (transcript.get(i).seq == message.seq) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            transcript.set(existing, message);
        } else {
            int insert = transcript.size();
            for (int i = 0; i < transcript.size(); i++) {
                if (transcript.get(i).seq > message.seq) {
                    insert = i;
                    break;
                }
            }
            transcript.add(insert, message);
        }
        boolean nearBottom = transcriptList == null || transcriptList.getLastVisiblePosition()
                >= transcript.size() - 3;
        if (transcriptAdapter != null) {
            transcriptAdapter.setStreamText(null);
            transcriptAdapter.notifyDataSetChanged();
        }
        if (transcriptList != null && nearBottom) {
            transcriptList.setSelection(transcript.size() - 1);
        }
        updateJumpPill(nearBottom);
        DshLog.messageAppend(currentSession == null ? "" : currentSession.id, message.seq);
        DshLog.appendVisible(currentSession == null ? "" : currentSession.id, message.seq,
                System.currentTimeMillis() - frameAt);
    }

    private void handleAssistantDelta(String data) {
        try {
            JSONObject json = new JSONObject(data);
            String sessionId = json.optString("sessionId", "");
            String text = json.optString("text", "");
            if (currentSession == null || !sessionId.equals(currentSession.id)) {
                DshLog.streamDrop(sessionId, "delta-not-open");
                return;
            }
            if (text.isEmpty() || transcriptAdapter == null) return;
            String current = transcriptAdapter.streamText();
            boolean nearBottom = transcriptList == null
                    || transcriptList.getLastVisiblePosition() >= transcript.size() - 3;
            transcriptAdapter.setStreamText((current == null ? "" : current) + text);
            if (transcriptList != null && nearBottom) {
                transcriptList.setSelection(transcript.size());
            } else {
                updateJumpPill(false);
            }
        } catch (Exception error) {
            Log.w(TAG, "bad assistant.delta frame", error);
        }
    }

    private void handleMessageReplace(String data) {
        try {
            JSONObject json = new JSONObject(data);
            String sessionId = json.optString("sessionId", "");
            if (currentSession == null || !sessionId.equals(currentSession.id)) return;
            long startSeq = json.optLong("startSeq", 0);
            long endSeq = json.optLong("endSeq", 0);
            JSONArray messages = json.optJSONArray("messages");
            if (messages == null) return;
            List<Models.Message> replacement = new ArrayList<>();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row != null) replacement.add(Models.Message.from(row));
            }
            for (int i = transcript.size() - 1; i >= 0; i--) {
                long seq = transcript.get(i).seq;
                if (seq >= startSeq && seq <= endSeq) transcript.remove(i);
            }
            for (Models.Message message : replacement) {
                boolean exists = false;
                for (Models.Message current : transcript) {
                    if (current.seq == message.seq) { exists = true; break; }
                }
                if (!exists) transcript.add(message);
            }
            java.util.Collections.sort(transcript, MainActivity::compareSeq);
            if (transcriptAdapter != null) transcriptAdapter.notifyDataSetChanged();
        } catch (Exception error) {
            Log.w(TAG, "bad message.replace frame", error);
        }
    }

    private void updateSessionPreview(String sessionId, String text) {
        if (data == null || sessionId == null || sessionId.isEmpty()) return;
        for (Models.Session session : data.sessions) {
            if (session.id.equals(sessionId)) {
                session.updatedAt = System.currentTimeMillis();
                if (text != null && !text.isEmpty()) session.preview = text;
                if (tab == TAB_CHATS && !chatOpen) refreshChannelsInPlace();
                return;
            }
        }
    }

    private void handleSessionUpdate(String payload, String event) {
        try {
            JSONObject json = new JSONObject(payload);
            String sessionId = json.optString("sessionId", "");
            if (data == null || sessionId.isEmpty()) return;
            for (Models.Session session : data.sessions) {
                if (!session.id.equals(sessionId)) continue;
                if (json.has("running")) session.running = json.optBoolean("running", session.running);
                if (json.has("waiting")) session.waiting = json.optBoolean("waiting", session.waiting);
                if (json.has("title")) session.title = json.optString("title", session.title);
                if (json.has("updatedAt")) session.updatedAt = json.optLong("updatedAt", session.updatedAt);
                if ("session.upsert".equals(event)) {
                    if (json.has("lastMessagePreview") && !json.isNull("lastMessagePreview")) {
                        session.preview = json.optString("lastMessagePreview", session.preview);
                    }
                    if (json.has("teamId") && !json.isNull("teamId")) {
                        session.teamId = json.optString("teamId", session.teamId);
                    }
                    if (json.has("teamRole") && !json.isNull("teamRole")) {
                        session.teamRole = json.optString("teamRole", session.teamRole);
                    }
                }
                if (tab == TAB_CHATS && !chatOpen) refreshChannelsInPlace();
                return;
            }
        } catch (Exception error) {
            Log.w(TAG, "bad session frame", error);
        }
    }

    /**
     * AC-2 debug-only hook (architect-approved G8): seed one local streaming row + a scrolled-up
     * transcript so QA can script chat_row:streaming and chat_jump_latest deterministically.
     * Guarded by AppConfig.DEBUG, no network mutation, marked source=debug-hook; remove for M4.
     */
    /** Routes debug deep links (restart banner fixture vs streaming hook); DEBUG-only. */
    private void handleDebugUri(android.net.Uri uri) {
        if (!AppConfig.DEBUG || uri == null) return;
        String last = uri.getLastPathSegment();
        if ("restart".equals(last)) {
            if (data == null) {
                pendingDebugUri = uri;
                return;
            }
            pluginRestartRequired = true;
            DshLog.marker("EVENT debug.restart.seed source=debug-hook");
            showTab(TAB_PLUGINS);
            Toast.makeText(this, "debug restart banner seeded", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("create-fail".equals(last)) {
            // Synthetic typed create failure: proves the sheet's readable error surface without
            // mutating the host (same DEBUG-only pattern as debug/restart). Single-shot.
            int status = 409;
            try {
                status = Integer.parseInt(uri.getQueryParameter("status"));
            } catch (Exception ignored) {
                // keep default
            }
            String code = uri.getQueryParameter("code");
            if (code == null || code.isEmpty()) code = "agent-preset-unavailable";
            String message = uri.getQueryParameter("message");
            if (message == null || message.isEmpty()) message = "preset unavailable (debug fixture)";
            JSONObject body = new JSONObject();
            try {
                body.put("ok", false);
                if ("1".equals(uri.getQueryParameter("nested"))) {
                    // v1ah envelope shape for C3: {ok:false, error:{code,message,retryable}}.
                    JSONObject inner = new JSONObject();
                    inner.put("code", code);
                    inner.put("message", message);
                    inner.put("retryable", false);
                    body.put("error", inner);
                } else {
                    body.put("code", code);
                    body.put("message", message);
                    body.put("retryable", false);
                }
            } catch (Exception ignored) {
                // JSONObject.put on strings cannot fail here
            }
            debugCreateFailure = "HTTP " + status + ": " + body;
            DshLog.marker("EVENT debug.create.fail.seed source=debug-hook status=" + status
                    + " code=" + code + " nested=" + ("1".equals(uri.getQueryParameter("nested"))));
            Toast.makeText(this, "debug create failure seeded", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("presets-broken".equals(last)) {
            // Synthetic roster: 7 rows, two broken with long human reasons (the shape live v1ag
            // produced before the preset fix). Proves the disabled-row + reason rendering
            // deterministically; no host call, no host mutation. Persists until app restart.
            try {
                JSONArray rows = new JSONArray();
                rows.put(debugPreset("glm-53-flash", "GLM 5.3 Flash",
                        "Default — inherits the host default model", true, null));
                rows.put(debugPreset("cordis", null, null, false, null));
                rows.put(debugPreset("deepseek-v41", "DeepSeek V4.1 Flash (1M)",
                        "buddy-gateway · DeepSeek-V4.1-Flash-1017", false,
                        "4f9e56a0 (@deepseek-ai/dsh-agent-default-model): service "
                                + "\"agentDefaultModel\" has been registered at "
                                + "<AgentDefaultModelConfig>"));
                rows.put(debugPreset("minimal", null, null, false, null));
                rows.put(debugPreset("ptc", null, null, false, null));
                rows.put(debugPreset("qwen38", "Qwen 3.8 Flash Next (256k)",
                        "buddy-gateway · Qwen3.8-Flash-Next", false,
                        "bff875eb (@deepseek-ai/dsh-agent-default-model): service "
                                + "\"agentDefaultModel\" has been registered at "
                                + "<AgentDefaultModelConfig>"));
                rows.put(debugPreset("standard", null, null, false, null));
                JSONObject roster = new JSONObject();
                roster.put("ok", true);
                roster.put("managementAvailable", false);
                roster.put("presets", rows);
                debugPresetRoster = roster.toString();
                DshLog.marker("EVENT debug.presets.broken.seed source=debug-hook rows=7 broken=2");
                Toast.makeText(this, "debug broken-preset roster seeded", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Log.w(TAG, "debug preset fixture failed", error);
            }
            return;
        }
        if ("presets-unknown".equals(last)) {
            // Synthetic roster containing an id the live host does not know. Selecting it and
            // pressing Create exercises the REAL v1ah 404 (agent-preset-not-found) end-to-end,
            // proving the sheet's typed-error copy against the live host, not a fixture body.
            try {
                JSONArray rows = new JSONArray();
                rows.put(debugPreset("glm-53-flash", "GLM 5.3 Flash",
                        "Default — inherits the host default model", true, null));
                rows.put(debugPreset("cordis", null, null, false, null));
                rows.put(debugPreset("no-such-preset", "Phantom model",
                        "not on this host — exercises the live v1ah 404", false, null));
                rows.put(debugPreset("minimal", null, null, false, null));
                rows.put(debugPreset("ptc", null, null, false, null));
                rows.put(debugPreset("qwen38", "Qwen 3.8 Flash Next (256k)",
                        "buddy-gateway · Qwen3.8-Flash-Next", false, null));
                rows.put(debugPreset("standard", null, null, false, null));
                JSONObject roster = new JSONObject();
                roster.put("ok", true);
                roster.put("managementAvailable", false);
                roster.put("presets", rows);
                debugPresetRoster = roster.toString();
                DshLog.marker("EVENT debug.presets.unknown.seed source=debug-hook rows=7");
                Toast.makeText(this, "debug unknown-preset roster seeded", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Log.w(TAG, "debug unknown-preset fixture failed", error);
            }
            return;
        }
        handleDebugStream(uri);
    }

    /** One synthetic GET /presets row for the DEBUG-only broken-roster fixture. */
    private static JSONObject debugPreset(String id, String name, String description,
                                          boolean isDefault, String brokenReason) throws Exception {
        JSONObject row = new JSONObject();
        row.put("id", id);
        if (name != null) row.put("name", name);
        if (description != null) row.put("description", description);
        row.put("isDefault", isDefault);
        if (brokenReason != null) row.put("broken", brokenReason);
        return row;
    }

    private void handleDebugStream(android.net.Uri uri) {
        if (!AppConfig.DEBUG) return;
        String sessionId = uri.getQueryParameter("session");
        String text = uri.getQueryParameter("text");
        if (text == null || text.isEmpty()) text = "debug streaming row";
        final String chunk = text;
        if (data == null || data.sessions == null || sessionId == null) {
            // Deep links can arrive before bootstrap finishes; replay once data is loaded.
            pendingDebugUri = uri;
            return;
        }
        Models.Session target = null;
        for (Models.Session session : data.sessions) {
            if (session.id.equals(sessionId)) { target = session; break; }
        }
        if (target == null) {
            Toast.makeText(this, "debug stream: session not found", Toast.LENGTH_SHORT).show();
            return;
        }
        openSession(target);
        final Models.Session session = target;
        // Seed only after the transcript page has landed; loadMessages replaces the adapter, so a
        // fixed delay races on longer transcripts. Retry until loaded (bounded), then seed.
        streamHandler.postDelayed(() -> seedDebugStreamWhenReady(session, chunk, 0), 400L);
    }

    private void seedDebugStreamWhenReady(Models.Session session, String chunk, int attempt) {
        if (!AppConfig.DEBUG) return;
        if (!chatOpen || currentSession == null || !currentSession.id.equals(session.id)) return;
        if ((transcriptLoading || transcriptAdapter == null) && attempt < 12) {
            streamHandler.postDelayed(
                    () -> seedDebugStreamWhenReady(session, chunk, attempt + 1), 400L);
            return;
        }
        seedDebugStream(session, chunk);
    }

    private void seedDebugStream(Models.Session session, String chunk) {
        if (!AppConfig.DEBUG) return;
        if (!chatOpen || currentSession == null || !currentSession.id.equals(session.id)) return;
        if (transcriptAdapter == null) {
            transcriptAdapter = new MessageAdapter(session.id, transcript);
            if (transcriptList != null) transcriptList.setAdapter(transcriptAdapter);
        }
        transcriptAdapter.setStreamText(chunk);
        if (transcriptList != null) transcriptList.setSelection(0);
        updateJumpPill(false);
        DshLog.marker("EVENT debug.stream.seed source=debug-hook sessionId=" + session.id
                + " textChars=" + chunk.length());
        DshLog.streamFrame(-1, "assistant.delta");
    }

    private void load() {
        topTitle.setText(R.string.app_name);
        topSubtitle.setText(R.string.connect_working);
        DshLog.bootstrapRequest();
        final long started = System.currentTimeMillis();
        io.execute(() -> {
            try {
                if (cookie.isEmpty() && !token.isEmpty()) {
                    api.pair(token);
                    cookie = api.cookie();
                    prefs.save(baseUrl, token, cookie);
                }
                JSONObject body = api.bootstrap();
                Models.Bootstrap bootstrap = Models.Bootstrap.from(body);
                runOnUiThread(() -> {
                    data = bootstrap;
                    DshLog.bootstrapDone(System.currentTimeMillis() - started,
                            bootstrap.sessions.size(), bootstrap.agents.size(),
                            bootstrap.plugins.size());
                    Log.d(TAG, "bootstrap sessions=" + bootstrap.sessions.size()
                            + " agents=" + bootstrap.agents.size()
                            + " plugins=" + bootstrap.plugins.size()
                            + " jobs=" + bootstrap.jobs.size());
                    render();
                    refreshDecisionsBadge();
                });
            } catch (Exception error) {
                String message = String.valueOf(error.getMessage());
                int kind = ERROR_OFFLINE;
                if (message.contains("HTTP 401")) {
                    kind = ERROR_401;
                    DshLog.authRejected(401, "/bootstrap");
                } else if (message.contains("HTTP 403")) {
                    kind = ERROR_403;
                    DshLog.authRejected(403, "/bootstrap");
                }
                Log.w(TAG, "bootstrap failed", error);
                final int errorKind = kind;
                runOnUiThread(() -> showError(errorKind, message));
            }
        });
    }

    private void render() {
        if (data == null) return;
        topTitle.setText(data.hostName);
        topSubtitle.setText((data.hostProfile.isEmpty() ? "dsh" : data.hostProfile)
                + " · " + data.sessions.size() + " sessions");
        topSubtitle.setContentDescription("Connection status: connected to " + data.hostName);
        renderRail();
        showTab(tab);
        if (pendingOpenSessionId != null) {
            Models.Session target = findSession(pendingOpenSessionId);
            pendingOpenSessionId = null;
            if (target != null) {
                openSession(target);
                return;
            }
        }
        if (pendingDebugUri != null) {
            final android.net.Uri deferred = pendingDebugUri;
            pendingDebugUri = null;
            streamHandler.post(() -> handleDebugUri(deferred));
        }
    }

    /**
     * UI overhaul (spec §2): the rail is a workspace navigator — one labeled row per host
     * workspace (all of them, scrollable inside @id/rail_scroll), then the pinned Home/Tools
     * rows from the layout. No plugin entries, no sessions. Descs are type-first:
     * `workspace <Title>` / `home all spaces` / `tools drawer` / `host <name>`.
     */
    private void renderRail() {
        if (railSpaces == null) return;
        railSpaces.removeAllViews();
        if (data != null) {
            View railHost = findViewById(R.id.rail_host);
            if (railHost != null) railHost.setContentDescription("host " + data.hostName);
            for (int i = 0; i < data.workspaces.size(); i++) {
                final Models.Workspace workspace = data.workspaces.get(i);
                String name = workspaceTitle(workspace);
                boolean active = filterCwd != null && filterCwd.equals(workspace.path);
                railSpaces.addView(railSpaceItem(name, active, "workspace " + name,
                        () -> {
                            filterCwd = workspace.path;
                            showTab(TAB_CHATS);
                            hideRailAfterSelect();
                        }));
            }
        }
        applyRailRowState(findViewById(R.id.rail_home), R.id.rail_home_icon, filterCwd == null);
        applyRailRowState(findViewById(R.id.rail_tools), R.id.rail_tools_icon, false);
        updateRailToolsBadge();
    }

    /** Display title of a workspace: Models.Workspace.title, else the path basename. */
    private static String workspaceTitle(Models.Workspace workspace) {
        if (workspace.title != null && !workspace.title.isEmpty()) return workspace.title;
        String path = workspace.path == null ? "" : workspace.path;
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    /** Active/inactive paint for a pinned rail row (Home/Tools): pill + tint + weight. */
    private void applyRailRowState(View row, int iconId, boolean active) {
        if (row == null) return;
        View pill = row.findViewById(R.id.rail_selected_indicator);
        if (pill != null) pill.setVisibility(active ? View.VISIBLE : View.GONE);
        TextView label = row.findViewById(R.id.rail_item_label);
        if (label != null) {
            label.setTextColor(getColor(active ? R.color.accent : R.color.text_soft));
            label.setTypeface(Typeface.create("sans-serif-medium",
                    active ? Typeface.BOLD : Typeface.NORMAL));
        }
        View icon = row.findViewById(iconId);
        if (icon instanceof ImageView) {
            ((ImageView) icon).setColorFilter(getColor(active ? R.color.accent : R.color.text_soft));
        }
    }

    /** Amber dot on the Tools row when decisions are waiting on the user (R2/R3 retarget). */
    private void updateRailToolsBadge() {
        View badge = findViewById(R.id.rail_tools_badge);
        if (badge != null) badge.setVisibility(decisionsWaiting > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * R4 evolved (spec §2.2): 48dp-min full-width rail target with an icon tile AND a readable
     * label (@id/rail_item_label) plus the active pill (@id/rail_selected_indicator). The label
     * ellipsizes on screen; the content-desc always carries the full workspace title.
     */
    private View railSpaceItem(String title, boolean active, String description, Runnable action) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setMinimumHeight(dp(48));
        frame.setId(R.id.rail_item);
        frame.setContentDescription(description);
        frame.setOnClickListener(v -> action.run());
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(2), dp(6), dp(2), dp(6));
        frame.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));
        TextView tile = new TextView(this);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        tile.setBackgroundResource(R.drawable.bg_logo);
        tile.setGravity(Gravity.CENTER);
        tile.setText(title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        tile.setTextColor(getColor(active ? R.color.accent : R.color.text_soft));
        tile.setTextSize(11);
        tile.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(tile);
        TextView label = new TextView(this);
        label.setId(R.id.rail_item_label);
        label.setText(title);
        label.setTextSize(11);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextColor(getColor(active ? R.color.accent : R.color.text_soft));
        label.setTypeface(Typeface.create("sans-serif-medium",
                active ? Typeface.BOLD : Typeface.NORMAL));
        label.setPadding(dp(4), dp(4), dp(4), 0);
        column.addView(label);
        if (active) {
            View bar = new View(this);
            bar.setId(R.id.rail_selected_indicator);
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(dp(3), dp(28),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(getColor(R.color.accent));
            frame.addView(bar, barParams);
        }
        return frame;
    }

    /** R4: host-logo long-press → Reconnect / Copy URL. */
    private void showRailMenu() {
        new AlertDialog.Builder(this)
                .setTitle(data == null || data.hostName.isEmpty() ? "Host" : data.hostName)
                .setItems(new String[]{getString(R.string.reconnect),
                        getString(R.string.copy_host_url)}, (dialog, which) -> {
                    if (which == 0) {
                        DshLog.marker("EVENT host.reconnect source=rail");
                        stopStream("manual");
                        reconnectAttempt = 0;
                        load();
                        startStream();
                        Toast.makeText(this, R.string.connect_working, Toast.LENGTH_SHORT).show();
                    } else {
                        copyToClipboard("host", baseUrl);
                    }
                })
                .show();
    }

    /**
     * UI overhaul (spec §3 + rail-ia-decision): the Tools drawer — the explicit home of the four
     * tool surfaces (Agents, Plugins, Jobs, Decisions). Rendered as a bottom sheet reusing
     * @id/drawer_scrim for dismissal; rows are @id/tools_row with content-desc `tool <Name>`.
     * The relocated #decisions waiting state lives in the Decisions row caption + the amber
     * rail_tools_badge dot. Plugin *cards* stay in the Plugins tab — the drawer names
     * destinations, never plugin instances.
     */
    private void showToolsDrawer() {
        DshLog.marker("EVENT tools.open");
        FrameLayout overlay = new FrameLayout(this);
        overlay.setId(R.id.drawer_scrim);
        overlay.setBackgroundColor(getColor(R.color.scrim));
        ScrollView scroll = new ScrollView(this);
        LinearLayout drawer = new LinearLayout(this);
        drawer.setId(R.id.tools_drawer);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundResource(R.drawable.bg_sheet);
        drawer.setPadding(dp(8), dp(8), dp(8), dp(12));
        View handle = new View(this);
        handle.setBackgroundResource(R.drawable.bg_handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        drawer.addView(handle, handleParams);
        TextView title = new TextView(this);
        title.setText(R.string.tools_drawer_title);
        title.setTextSize(14);
        title.setTextColor(getColor(R.color.text));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(12), dp(2), dp(12), dp(8));
        drawer.addView(title);
        final AlertDialog[] dialogRef = new AlertDialog[1];
        drawer.addView(toolRow("A", getString(R.string.tools_row_agents),
                getString(R.string.tools_row_agents_sub), R.color.muted, "tool Agents",
                () -> pickTool(dialogRef, "agents", () -> showTab(TAB_AGENTS))));
        drawer.addView(toolRow("P", getString(R.string.tools_row_plugins),
                getString(R.string.tools_row_plugins_sub), R.color.muted, "tool Plugins",
                () -> pickTool(dialogRef, "plugins", () -> showTab(TAB_PLUGINS))));
        drawer.addView(toolRow("J", getString(R.string.tools_row_jobs),
                getString(R.string.tools_row_jobs_sub), R.color.muted, "tool Jobs",
                () -> pickTool(dialogRef, "jobs", () -> showTab(TAB_JOBS))));
        boolean waiting = decisionsWaiting > 0;
        String decisionsCaption = waiting
                ? getString(R.string.tools_row_decisions_waiting_fmt, decisionsWaiting)
                        + (decisionsUrgent > 0 ? " · urgent" : "")
                : getString(R.string.tools_row_decisions_sub);
        drawer.addView(toolRow("D", getString(R.string.tools_row_decisions),
                decisionsCaption, waiting ? R.color.amber : R.color.muted, "tool Decisions",
                () -> pickTool(dialogRef, "decisions", this::showDecisions)));
        scroll.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        overlay.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        dialogRef[0] = new AlertDialog.Builder(this)
                .setView(overlay)
                .create();
        dialogRef[0].show();
        android.view.Window window = dialogRef[0].getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    Color.TRANSPARENT));
        }
    }

    /** Drawer closes before navigating (spec §3): dismiss, marker, then deep-link. */
    private void pickTool(AlertDialog[] dialogRef, String target, Runnable nav) {
        DshLog.marker("EVENT tools.pick target=" + target);
        if (dialogRef[0] != null && dialogRef[0].isShowing()) dialogRef[0].dismiss();
        nav.run();
    }

    /** One @id/tools_row: 52dp min, tile glyph + 14sp name + 11sp caption. */
    private View toolRow(String glyph, String name, String caption, int captionColorRes,
                         String desc, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setId(R.id.tools_row);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setContentDescription(desc);
        row.setTag(name.toLowerCase(java.util.Locale.ROOT));
        row.setOnClickListener(v -> action.run());
        TextView tile = new TextView(this);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        tile.setBackgroundResource(R.drawable.bg_tile);
        tile.setGravity(Gravity.CENTER);
        tile.setText(glyph);
        tile.setTextColor(getColor(R.color.text_soft));
        tile.setTextSize(11);
        tile.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(tile);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.leftMargin = dp(12);
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(14);
        nameView.setTextColor(getColor(R.color.text));
        column.addView(nameView);
        TextView captionView = new TextView(this);
        captionView.setText(caption);
        captionView.setTextSize(11);
        captionView.setTextColor(getColor(captionColorRes));
        column.addView(captionView);
        row.addView(column, columnParams);
        return row;
    }

    /**
     * Rail collapse (team/design/rail-collapse-spec.md): @id/server_rail GONE lets
     * @id/main_screen (0dp, weight 1) take the full width; the toggle stays in the top bar.
     * content-desc is state-aware: `show sidebar` when hidden, `hide sidebar` when shown.
     */
    private void applyRailHidden(boolean hidden) {
        View rail = findViewById(R.id.server_rail);
        if (rail != null) rail.setVisibility(hidden ? View.GONE : View.VISIBLE);
        View toggle = findViewById(R.id.rail_toggle);
        if (toggle != null) {
            toggle.setContentDescription(getString(hidden
                    ? R.string.rail_toggle_show_desc : R.string.rail_toggle_hide_desc));
        }
    }

    private void toggleRail() {
        boolean hidden = !prefs.railHidden();
        prefs.setRailHidden(hidden);
        applyRailHidden(hidden);
        DshLog.marker("EVENT rail.toggle hidden=" + hidden);
    }

    /** Phone pattern: a rail selection (workspace / Home / Tools) hides the rail after it applies. */
    private void hideRailAfterSelect() {
        if (prefs.railHidden()) return;
        prefs.setRailHidden(true);
        applyRailHidden(true);
        DshLog.marker("EVENT rail.hide reason=select");
    }

    private void showTab(int index) {
        DshLog.marker("EVENT tab.select index=" + index + " chatOpenBefore=" + chatOpen);
        tab = index;
        showingDecisions = false;
        chatOpen = false;
        currentSession = null;
        backButton.setVisibility(View.GONE);
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(getColor(i == tab ? R.color.accent : R.color.muted));
            // Active-tab accent bar (spec §5) — pill-equivalent, never color-only.
            tabs[i].setBackgroundResource(i == tab ? R.drawable.bg_tab_active : 0);
            tabs[i].setSelected(i == tab);
        }
        if (data != null) {
            topTitle.setText(data.hostName);
            topSubtitle.setText((data.hostProfile.isEmpty() ? "dsh" : data.hostProfile)
                    + " · " + data.sessions.size() + " sessions");
        }
        content.removeAllViews();
        switch (index) {
            case TAB_AGENTS:
                showAgents();
                break;
            case TAB_PLUGINS:
                showPlugins();
                break;
            case TAB_JOBS:
                showJobs();
                break;
            case TAB_YOU:
                showYou();
                break;
            default:
                showChannels();
                break;
        }
    }

    // ---------------------------------------------------------------- channels

    private static final class Row {
        final int type;
        final String title;
        final String subtitle;
        final String special;
        final Models.Session session;
        final int indent;

        Row(int type, String title, String subtitle, String special, Models.Session session) {
            this(type, title, subtitle, special, session, 0);
        }

        Row(int type, String title, String subtitle, String special, Models.Session session,
            int indent) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.special = special;
            this.session = session;
            this.indent = indent;
        }
    }

    private List<Row> channelRows() {
        List<Row> rows = new ArrayList<>();
        // UI overhaul (rail-ia-decision): the `#` system rows (decisions/jobs/agents/plugins)
        // moved to the Tools drawer — channelRows() renders only the §3.4 groups (+ Test).
        // channel_group_header texts are therefore exactly WAITING/TEAM/RUNNING/RECENT (+TEST
        // as its own channel_test_toggle row, ADR-0010).
        List<Models.Session> filtered = new ArrayList<>();
        if (data != null) {
            for (Models.Session session : data.sessions) {
                if (session.plainSubagent()) continue;
                if (filterCwd != null && !filterCwd.equals(session.cwd)) continue;
                if (!query.isEmpty() && !session.title.toLowerCase().contains(query)) continue;
                filtered.add(session);
            }
        }
        // ADR-0010 §2: partition, don't re-group — the four ADR-0008 groups are computed from
        // `main` only, so a classified session appears in exactly one place. N (the TEST count)
        // is scope-aware: the same query + workspace filters applied to main apply to test.
        List<Models.Session> main = new ArrayList<>();
        List<Models.Session> test = new ArrayList<>();
        for (Models.Session session : filtered) {
            if (TestClassifier.isTest(session)) test.add(session);
            else main.add(session);
        }
        // PO v1.3 / contract §3.4 (ADR-0008 + amendment): Waiting > Team > Running > Recent > Test.
        addGroup(rows, R.string.group_waiting, R.string.group_waiting_sub, main,
                session -> session.waiting);
        addTeamGroup(rows, main);
        addGroup(rows, R.string.group_running, R.string.group_running_sub, main,
                session -> !session.waiting && session.teamId == null && session.running);
        addGroup(rows, R.string.group_recent, R.string.group_recent_sub, main,
                session -> !session.waiting && session.teamId == null && !session.running);
        if (main.isEmpty()) {
            rows.add(new Row(ROW_MESSAGE, getString(R.string.no_sessions_title), getString(R.string.no_sessions_body), null, null));
        }
        // ADR-0010 §2: the TEST block renders after Recent, last — a single toggle row when
        // N > 0 (empty scope → no row, same rule as empty groups). Expanded, the classified
        // sessions render as ordinary channel_rows below the toggle.
        if (!test.isEmpty()) {
            boolean expanded = prefs.showTestSessions();
            String state = expanded ? "expanded" : "collapsed";
            DshLog.channelGroup(getString(R.string.group_test), test.size());
            rows.add(new Row(ROW_TEST_TOGGLE,
                    getString(R.string.group_test_fmt, test.size()),
                    String.valueOf(test.size()), state, null));
            if (expanded) {
                for (Models.Session session : test) {
                    rows.add(new Row(ROW_CHANNEL, session.title,
                            TextUtils.isEmpty(session.preview) ? null : session.preview,
                            null, session));
                }
            }
        }
        return rows;
    }

    /** ADR-0010 §2: expand/collapse the Test group in place; state persists (Prefs). */
    private void toggleTestChannel(int count) {
        boolean next = !prefs.showTestSessions();
        prefs.setShowTestSessions(next);
        DshLog.marker("EVENT test.toggle state=" + (next ? "expanded" : "collapsed")
                + " count=" + count);
        refreshChannelsInPlace();
    }

    /** Depth for a TEAM row: API delegationDepth when present, else parentSessionId chain. */
    private int teamDepthOf(Models.Session session, List<Models.Session> team) {
        if (session.delegationDepth >= 0) return Math.min(session.delegationDepth, 2);
        if (session.parentSessionId != null) {
            for (Models.Session parent : team) {
                if (parent.id.equals(session.parentSessionId)) {
                    return Math.min(teamDepthOf(parent, team) + 1, 2);
                }
            }
        }
        return 0;
    }

    /** Team: Lead rows first, then by delegationDepth/parentSessionId; indent capped at 2 (§3.4). */
    private void addTeamGroup(List<Row> rows, List<Models.Session> sessions) {
        List<Models.Session> team = new ArrayList<>();
        for (Models.Session session : sessions) {
            if (!session.waiting && session.teamId != null) team.add(session);
        }
        if (team.isEmpty()) return;
        DshLog.channelGroup(getString(R.string.group_team), team.size());
        rows.add(new Row(ROW_SECTION, getString(R.string.group_team),
                getString(R.string.group_team_sub), null, null));
        final List<Models.Session> teamRef = team;
        List<Models.Session> roots = new ArrayList<>();
        List<Models.Session> nested = new ArrayList<>();
        for (Models.Session session : team) {
            if (teamDepthOf(session, teamRef) == 0) roots.add(session);
            else nested.add(session);
        }
        java.util.Collections.sort(roots, (a, b) -> {
            boolean leadA = a.teamRole != null && "lead".equalsIgnoreCase(a.teamRole);
            boolean leadB = b.teamRole != null && "lead".equalsIgnoreCase(b.teamRole);
            if (leadA != leadB) return leadA ? -1 : 1;
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        java.util.Collections.sort(nested, (a, b) -> {
            int depthA = teamDepthOf(a, teamRef);
            int depthB = teamDepthOf(b, teamRef);
            if (depthA != depthB) return Integer.compare(depthA, depthB);
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        java.util.Set<String> emitted = new java.util.HashSet<>();
        // Adjacency: each root (Lead) immediately followed by its nested children so depth 1 is
        // visible in the top window and scriptable; orphaned nested rows follow their parent group.
        for (Models.Session root : roots) {
            rows.add(new Row(ROW_CHANNEL, root.title,
                    TextUtils.isEmpty(root.preview) ? null : root.preview, null, root, 0));
            emitted.add(root.id);
            for (Models.Session child : nested) {
                if (child.parentSessionId != null && child.parentSessionId.equals(root.id)) {
                    rows.add(new Row(ROW_CHANNEL, child.title,
                            TextUtils.isEmpty(child.preview) ? null : child.preview, null, child,
                            teamDepthOf(child, teamRef)));
                    emitted.add(child.id);
                }
            }
        }
        for (Models.Session child : nested) {
            if (emitted.contains(child.id)) continue;
            rows.add(new Row(ROW_CHANNEL, child.title,
                    TextUtils.isEmpty(child.preview) ? null : child.preview, null, child,
                    teamDepthOf(child, teamRef)));
        }
    }

    private void addGroup(List<Row> rows, int titleRes, int subtitleRes,
                          List<Models.Session> sessions,
                          java.util.function.Predicate<Models.Session> predicate) {
        List<Models.Session> group = new ArrayList<>();
        for (Models.Session session : sessions) {
            if (predicate.test(session)) group.add(session);
        }
        if (group.isEmpty()) return;
        DshLog.channelGroup(getString(titleRes), group.size());
        rows.add(new Row(ROW_SECTION, getString(titleRes), getString(subtitleRes), null, null));
        for (Models.Session session : group) {
            rows.add(new Row(ROW_CHANNEL, session.title,
                    TextUtils.isEmpty(session.preview) ? null : session.preview, null, session));
        }
    }

    private void showChannels() {
        List<Row> rows = channelRows();
        FrameLayout frame = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setId(R.id.channel_list);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(getColor(R.color.bg));
        TextView empty = emptyView(getString(R.string.no_sessions_title), getString(R.string.no_sessions_body));
        empty.setId(R.id.channel_empty);
        frame.addView(list);
        frame.addView(empty);
        list.setEmptyView(empty);
        channelAdapter = new ChannelAdapter(rows);
        list.setAdapter(channelAdapter);
        channelListView = list;
        channelRowsLive = rows;
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            if (row.type == ROW_TEST_TOGGLE) {
                int count = 0;
                try {
                    count = Integer.parseInt(row.subtitle);
                } catch (NumberFormatException ignored) {
                }
                toggleTestChannel(count);
                return;
            }
            if (row.type == ROW_SECTION || row.type == ROW_MESSAGE) return;
            if (row.session != null) {
                openSession(row.session);
                return;
            }
            switch (row.special) {
                case "decisions":
                    showDecisions();
                    break;
                case "jobs":
                    showTab(TAB_JOBS);
                    break;
                case "agents":
                    showTab(TAB_AGENTS);
                    break;
                case "plugins":
                    showTab(TAB_PLUGINS);
                    break;
                default:
                    break;
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            if (row.session != null) {
                showSessionMenu(row.session);
                return true;
            }
            return false;
        });
        // S17 / task-2: the new-session affordance (FAB, bottom-end above the tab band).
        ImageButton fab = new ImageButton(this);
        fab.setId(R.id.new_session_fab);
        fab.setContentDescription(getString(R.string.new_session));
        fab.setImageResource(R.drawable.ic_plus);
        fab.setBackgroundResource(R.drawable.bg_fab);
        fab.setPadding(dp(16), dp(16), dp(16), dp(16));
        fab.setElevation(dp(6));
        fab.setOnClickListener(v -> showNewSessionSheet(fab));
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(
                dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        fabParams.leftMargin = dp(16);
        fabParams.topMargin = dp(16);
        fabParams.rightMargin = dp(16);
        fabParams.bottomMargin = dp(16);
        frame.addView(fab, fabParams);
        // Keep the last rows scrollable clear of the FAB (standard clipped-FAB overlay).
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(72));
        content.removeAllViews();
        content.addView(frame);
    }

    /**
     * Default create target for the new-session sheet, in precedence order (task-5 phase 2,
     * PO AC-6): active rail space → persisted last-opened session cwd → main workspace
     * (non-blank, non-probe sessions; host scratch/system workspaces excluded) → first
     * workspace. `sourceOut[0]` receives rail|last-open|main|first|none for the evidence marker.
     */
    private Models.Workspace pickDefaultWorkspace(String[] sourceOut) {
        List<Models.Workspace> spaces = data == null ? new ArrayList<>() : data.workspaces;
        if (spaces.isEmpty()) {
            sourceOut[0] = "none";
            return null;
        }
        if (filterCwd != null && !filterCwd.isEmpty()) {
            for (Models.Workspace workspace : spaces) {
                if (filterCwd.equals(workspace.path)) { sourceOut[0] = "rail"; return workspace; }
            }
        }
        String last = prefs.lastCwd();
        if (last != null && !last.isEmpty()) {
            for (Models.Workspace workspace : spaces) {
                if (last.equals(workspace.path)) { sourceOut[0] = "last-open"; return workspace; }
            }
        }
        Models.Workspace main = mainWorkspace(spaces);
        if (main != null) {
            sourceOut[0] = "main";
            return main;
        }
        sourceOut[0] = "first";
        return spaces.get(0);
    }

    /**
     * "Main workspace" = the workspace with the most real sessions, where real = non-blank and
     * not a probe/test seed. Host scratch/system workspaces (*…/.hermes/…) never win — they stay
     * selectable in the picker but must not be the heuristic default.
     */
    private Models.Workspace mainWorkspace(List<Models.Workspace> spaces) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        if (data != null) {
            for (Models.Session session : data.sessions) {
                if (session.blank) continue;
                if (session.cwd == null || session.cwd.isEmpty()) continue;
                if (isSystemWorkspace(session.cwd)) continue;
                String title = session.title == null ? "" : session.title.toLowerCase(java.util.Locale.ROOT);
                if (title.startsWith("m2gate") || title.startsWith("probe")) continue;
                Integer current = counts.get(session.cwd);
                counts.put(session.cwd, current == null ? 1 : current + 1);
            }
        }
        Models.Workspace best = null;
        int bestCount = 0;
        for (Models.Workspace workspace : spaces) {
            if (isSystemWorkspace(workspace.path)) continue;
            Integer count = counts.get(workspace.path);
            int value = count == null ? 0 : count;
            if (value > bestCount) { best = workspace; bestCount = value; }
        }
        return best;
    }

    private static boolean isSystemWorkspace(String path) {
        return path != null && path.contains("/.hermes/");
    }

    /**
     * Task-5: "New session" sheet — optional name + agentPreset picker. The model section is
     * populated from GET /presets only when capabilities.agentPresets is on AND the endpoint
     * answers; on 404/empty/failure it stays hidden and creation behaves exactly like the
     * task-2 FAB (no new failure modes).
     */
    private void showNewSessionSheet(View fab) {
        boolean presetsCapable = data != null && data.agentPresets;
        DshLog.sheetOpen(presetsCapable);
        // task-11 fix: the sheet body scrolls; error + Create/Cancel stay pinned below the fold
        // so Create is reachable on small windows (the large phone 1080×2094) and with the IME open.
        LinearLayout sheet = new LinearLayout(this);
        sheet.setId(R.id.new_session_sheet);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(12), dp(20), dp(8));

        final ScrollView sheetScroll = new ScrollView(this);
        sheetScroll.setId(R.id.new_session_sheet_scroll);
        sheetScroll.setVerticalScrollBarEnabled(true);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        sheetScroll.addView(scrollContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(sheetScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout sheetBody = scrollContent;

        final EditText nameInput = new EditText(this);
        nameInput.setId(R.id.session_name_input);
        nameInput.setHint(R.string.session_name_hint);
        nameInput.setTextSize(14);
        nameInput.setTextColor(getColor(R.color.text));
        nameInput.setHintTextColor(getColor(R.color.faint));
        nameInput.setBackgroundResource(R.drawable.bg_input);
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameInput.setContentDescription(getString(R.string.session_name_hint));
        sheetBody.addView(nameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // C5(a): expectation copy — a created session is blank (GUI-invisible) until first prompt.
        TextView visibilityHint = new TextView(this);
        visibilityHint.setId(R.id.session_visibility_hint);
        visibilityHint.setText(R.string.session_visibility_hint);
        visibilityHint.setTextSize(11);
        visibilityHint.setTextColor(getColor(R.color.muted));
        visibilityHint.setMaxLines(2);
        visibilityHint.setPadding(0, dp(4), 0, 0);
        sheetBody.addView(visibilityHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // WORKSPACE picker (task-5 phase 2, PO AC-6): the create target is explicit and visible.
        // Default order: rail filter → last-opened session cwd → main workspace (scratch excluded)
        // → first workspace. Scratch/system workspaces stay selectable, never the heuristic default.
        final LinearLayout workspaceSection = new LinearLayout(this);
        workspaceSection.setId(R.id.session_workspace);
        workspaceSection.setOrientation(LinearLayout.VERTICAL);
        workspaceSection.setVisibility(View.GONE);
        TextView workspaceLabel = new TextView(this);
        workspaceLabel.setText(R.string.workspace_section_label);
        workspaceLabel.setTextColor(getColor(R.color.muted));
        workspaceLabel.setTextSize(11);
        workspaceLabel.setLetterSpacing(0.06f);
        workspaceLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        workspaceLabel.setPadding(0, dp(14), 0, dp(4));
        workspaceSection.addView(workspaceLabel);
        final TextView workspaceSummary = new TextView(this);
        workspaceSummary.setId(R.id.session_workspace_summary);
        workspaceSummary.setTextSize(13);
        workspaceSummary.setTextColor(getColor(R.color.text));
        workspaceSummary.setPadding(0, 0, 0, dp(4));
        workspaceSummary.setMaxLines(2);
        workspaceSection.addView(workspaceSummary);
        final LinearLayout workspaceRows = new LinearLayout(this);
        workspaceRows.setOrientation(LinearLayout.VERTICAL);
        workspaceSection.addView(workspaceRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetBody.addView(workspaceSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final java.util.List<Models.Workspace> workspaces =
                data == null ? new ArrayList<>() : new ArrayList<>(data.workspaces);
        final java.util.Map<String, TextView> workspaceChecks = new java.util.LinkedHashMap<>();
        final Models.Workspace[] chosenWorkspace = {null};
        if (!workspaces.isEmpty()) {
            String[] defaultSource = new String[1];
            chosenWorkspace[0] = pickDefaultWorkspace(defaultSource);
            DshLog.sheetWorkspaceDefault(chosenWorkspace[0] == null ? "" : chosenWorkspace[0].path,
                    defaultSource[0]);
            for (Models.Workspace workspace : workspaces) {
                LinearLayout row = new LinearLayout(this);
                row.setId(R.id.workspace_row);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(44));
                row.setPadding(dp(4), dp(8), dp(4), dp(8));
                row.setContentDescription("workspace " + workspace.title + ", " + workspace.path);
                TextView check = new TextView(this);
                check.setId(R.id.workspace_check);
                check.setTextSize(14);
                check.setPadding(0, 0, dp(10), 0);
                row.addView(check, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout column = new LinearLayout(this);
                column.setOrientation(LinearLayout.VERTICAL);
                TextView title = new TextView(this);
                title.setText(workspace.title == null || workspace.title.isEmpty()
                        ? workspace.path : workspace.title);
                title.setTextSize(14);
                title.setTextColor(getColor(R.color.text));
                column.addView(title);
                TextView path = new TextView(this);
                path.setText(workspace.path);
                path.setTextSize(11);
                path.setTextColor(getColor(R.color.muted));
                path.setMaxLines(1);
                path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                column.addView(path);
                row.addView(column, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setOnClickListener(v -> {
                    chosenWorkspace[0] = workspace;
                    DshLog.workspacePick(workspace.path);
                    repaintWorkspaceChecks(workspaceChecks, workspaces, chosenWorkspace[0]);
                    updateWorkspaceSummary(workspaceSummary, chosenWorkspace[0]);
                });
                workspaceChecks.put(workspace.path, check);
                workspaceRows.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            repaintWorkspaceChecks(workspaceChecks, workspaces, chosenWorkspace[0]);
            updateWorkspaceSummary(workspaceSummary, chosenWorkspace[0]);
            workspaceSection.setVisibility(View.VISIBLE);
        }

        final LinearLayout presetSection = new LinearLayout(this);
        presetSection.setId(R.id.preset_section);
        presetSection.setOrientation(LinearLayout.VERTICAL);
        presetSection.setVisibility(View.GONE);
        TextView presetLabel = new TextView(this);
        presetLabel.setText(R.string.preset_section_label);
        presetLabel.setTextColor(getColor(R.color.muted));
        presetLabel.setTextSize(11);
        presetLabel.setLetterSpacing(0.06f);
        presetLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        presetLabel.setPadding(0, dp(14), 0, dp(4));
        presetSection.addView(presetLabel);
        final LinearLayout presetRows = new LinearLayout(this);
        presetRows.setOrientation(LinearLayout.VERTICAL);
        presetSection.addView(presetRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetBody.addView(presetSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // In-sheet failure surface (task-5 phase 2): typed preset/create errors stay here as
        // readable copy instead of dismissing the sheet into a generic toast. Pinned below the
        // scroll (task-11 fix) so a typed failure is readable without scrolling.
        final TextView errorView = new TextView(this);
        errorView.setId(R.id.session_create_error);
        errorView.setTextSize(12);
        errorView.setTextColor(getColor(R.color.red));
        errorView.setVisibility(View.GONE);
        errorView.setPadding(0, dp(10), 0, 0);
        sheet.addView(errorView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final java.util.List<Models.Preset> presets = new ArrayList<>();
        final java.util.Map<String, TextView> checks = new java.util.LinkedHashMap<>();
        final Models.Preset[] chosen = {null};
        final boolean[] busy = {false};
        final AlertDialog[] dialogRef = new AlertDialog[1];
        // C2: exactly one roster re-read per sheet lifetime after a typed 409; never reset.
        final boolean[] presetReloaded = {false};
        final Runnable[] presetReloader = new Runnable[1];

        if (presetsCapable) {
            // Initial load and the 409-triggered reload share this fetch+render path.
            final Runnable loadPresets = () -> io.execute(() -> {
                boolean reload = presetReloaded[0];
                try {
                    List<Models.Preset> loaded;
                    if (AppConfig.DEBUG && debugPresetRoster != null) {
                        // Synthetic roster fixture (DEBUG-only): the live host no longer reports
                        // broken presets after the v1ag preset fix, so QA needs a deterministic
                        // broken-row capture. Marked source=debug-hook; no network mutation.
                        loaded = Models.Preset.listFrom(new JSONObject(debugPresetRoster));
                        DshLog.marker("EVENT presets.debug source=debug-hook rows=" + loaded.size());
                    } else {
                        loaded = Models.Preset.listFrom(api.presets());
                    }
                    runOnUiThread(() -> {
                        int scrollY = sheetScroll.getScrollY();
                        presetRows.removeAllViews();
                        presets.clear();
                        checks.clear();
                        chosen[0] = null;
                        if (loaded.isEmpty()) {
                            presetSection.setVisibility(View.GONE);
                            if (reload) {
                                DshLog.marker("EVENT presets.reload count=0");
                            } else {
                                DshLog.presetsLoad(0);
                            }
                            return; // section stays hidden — degrade
                        }
                        presets.addAll(loaded);
                        for (Models.Preset preset : loaded) {
                            LinearLayout row = new LinearLayout(this);
                            row.setId(R.id.preset_row);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setMinimumHeight(dp(48));
                            row.setPadding(dp(4), dp(10), dp(4), dp(10));
                            TextView check = new TextView(this);
                            check.setId(R.id.preset_check);
                            check.setTextSize(14);
                            check.setPadding(0, 0, dp(10), 0);
                            row.addView(check, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                            LinearLayout column = new LinearLayout(this);
                            column.setOrientation(LinearLayout.VERTICAL);
                            TextView title = new TextView(this);
                            title.setText(preset.isDefault
                                    ? preset.label() + " · default"
                                    : preset.label());
                            title.setTextSize(14);
                            title.setTextColor(getColor(preset.broken ? R.color.faint : R.color.text));
                            column.addView(title);
                            // C1/C4: the visible secondary line is always human copy; the raw
                            // host diagnostic survives only in the row content-desc (and Details).
                            String secondary;
                            if (preset.broken) {
                                secondary = getString(R.string.preset_broken_host);
                            } else if (preset.modelLine() != null) {
                                secondary = getString(R.string.preset_model_fmt, preset.modelLine());
                            } else if (preset.description != null && !preset.description.isEmpty()) {
                                secondary = preset.description;
                            } else {
                                secondary = getString(R.string.preset_runs_default_model);
                            }
                            if (secondary != null && !secondary.isEmpty()) {
                                TextView desc = new TextView(this);
                                desc.setText(secondary);
                                desc.setTextSize(12);
                                desc.setTextColor(getColor(R.color.muted));
                                if (preset.broken) {
                                    desc.setMaxLines(2);
                                    desc.setEllipsize(TextUtils.TruncateAt.END);
                                }
                                column.addView(desc);
                            }
                            if (preset.broken && preset.reason != null && !preset.reason.isEmpty()) {
                                TextView details = new TextView(this);
                                details.setId(R.id.preset_details);
                                details.setText(R.string.preset_details_label);
                                details.setTextSize(11);
                                details.setTextColor(getColor(R.color.accent));
                                details.setContentDescription(getString(
                                        R.string.preset_details_desc_fmt, preset.label()));
                                details.setMinimumWidth(dp(32));
                                details.setMinimumHeight(dp(32));
                                details.setPadding(dp(8), 0, dp(8), 0);
                                details.setOnClickListener(v -> new AlertDialog.Builder(this)
                                        .setTitle(preset.label())
                                        .setMessage(preset.reason)
                                        .setPositiveButton(R.string.preset_details_close, null)
                                        .show());
                                column.addView(details, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                            }
                            row.addView(column, new LinearLayout.LayoutParams(0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            if (preset.broken) {
                                row.setEnabled(false);
                                row.setAlpha(0.45f);
                                // Accessibility/QA hook: the disabled row names its reason.
                                row.setContentDescription(preset.label() + ", unavailable: "
                                        + (preset.reason == null || preset.reason.isEmpty()
                                                ? "unavailable" : preset.reason));
                            } else {
                                row.setContentDescription(preset.label());
                                row.setOnClickListener(v -> {
                                    chosen[0] = preset;
                                    DshLog.presetPick(preset.isDefault ? null : preset.id);
                                    repaintPresetChecks(checks, presets, chosen[0]);
                                });
                            }
                            checks.put(preset.id, check);
                            presetRows.addView(row, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                        }
                        // Contract §3: pre-select the isDefault entry. If the default itself is
                        // broken, fall back to the first usable preset so Create can succeed.
                        Models.Preset initial = null;
                        for (Models.Preset preset : presets) {
                            if (preset.isDefault && !preset.broken) { initial = preset; break; }
                        }
                        if (initial == null) {
                            for (Models.Preset preset : presets) {
                                if (!preset.broken) { initial = preset; break; }
                            }
                        }
                        chosen[0] = initial;
                        repaintPresetChecks(checks, presets, chosen[0]);
                        presetSection.setVisibility(View.VISIBLE);
                        if (reload) {
                            DshLog.marker("EVENT presets.reload count=" + presets.size());
                        } else {
                            DshLog.presetsLoad(presets.size());
                        }
                        sheetScroll.scrollTo(0, scrollY);
                    });
                } catch (Exception error) {
                    if (reload) {
                        DshLog.marker("EVENT presets.reload.fail reason="
                                + String.valueOf(error.getMessage()).trim().replaceAll("\\s", "_"));
                    } else {
                        DshLog.presetsLoadFail(String.valueOf(error.getMessage()));
                    }
                    // Graceful degrade: the sheet stays preset-less; no error surface.
                }
            });
            presetReloader[0] = () -> {
                if (presetReloaded[0]) return;
                presetReloaded[0] = true;
                loadPresets.run();
            };
            loadPresets.run();
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(12), 0, 0);
        // Pinned footer (task-11 fix): Create/Cancel live outside the ScrollView.
        Button cancel = new Button(this);
        cancel.setText(R.string.dependency_cancel_label);
        styleDialogButton(cancel, false);
        cancel.setOnClickListener(v -> dialogRef[0].dismiss());
        buttons.addView(cancel);
        final Button create = new Button(this);
        create.setId(R.id.session_create);
        create.setText(R.string.session_create_label);
        styleDialogButton(create, true);
        create.setContentDescription(getString(R.string.session_create_label));
        create.setOnClickListener(v -> {
            if (busy[0]) return;
            busy[0] = true;
            create.setEnabled(false);
            create.setText(R.string.session_creating);
            errorView.setVisibility(View.GONE);
            String name = nameInput.getText().toString().trim();
            String presetId = chosen[0] == null || chosen[0].isDefault ? null : chosen[0].id;
            String cwd = chosenWorkspace[0] == null ? null : chosenWorkspace[0].path;
            // Keep the sheet open until the create settles: a typed preset failure must be
            // readable in place (task-5 phase 2), not a dismissed dialog plus a toast.
            doCreateSession(fab, name, presetId, cwd, dialogRef[0], create, errorView,
                    presetReloader[0]);
        });
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        createParams.leftMargin = dp(8);
        buttons.addView(create, createParams);
        sheet.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialogRef[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.new_session)
                .setView(sheet)
                .create();
        // task-11 fix: resize the dialog window (not pan) when the IME opens so the pinned
        // Create/Cancel row is never covered by the keyboard on 1080×2094.
        dialogRef[0].getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialogRef[0].show();
    }

    private void repaintPresetChecks(java.util.Map<String, TextView> checks,
                                     java.util.List<Models.Preset> presets,
                                     Models.Preset selected) {
        for (Models.Preset preset : presets) {
            TextView check = checks.get(preset.id);
            if (check == null) continue;
            boolean on = selected != null && selected.id.equals(preset.id);
            check.setText(on ? "✓" : "○");
            check.setTextColor(getColor(on ? R.color.accent : R.color.faint));
        }
    }

    private void repaintWorkspaceChecks(java.util.Map<String, TextView> checks,
                                        java.util.List<Models.Workspace> workspaces,
                                        Models.Workspace selected) {
        for (Models.Workspace workspace : workspaces) {
            TextView check = checks.get(workspace.path);
            if (check == null) continue;
            boolean on = selected != null && selected.path.equals(workspace.path);
            check.setText(on ? "✓" : "○");
            check.setTextColor(getColor(on ? R.color.accent : R.color.faint));
        }
    }

    private void updateWorkspaceSummary(TextView summary, Models.Workspace selected) {
        if (summary == null) return;
        if (selected == null) {
            summary.setVisibility(View.GONE);
            return;
        }
        String title = selected.title == null || selected.title.isEmpty()
                ? selected.path : selected.title;
        summary.setText(getString(R.string.workspace_create_in_path_fmt, title, selected.path));
        summary.setVisibility(View.VISIBLE);
    }

    private void styleDialogButton(Button button, boolean primary) {
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(getColor(primary ? R.color.on_accent : R.color.text_soft));
        button.setBackgroundResource(primary ? R.drawable.bg_button : R.drawable.bg_button_ghost);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
    }

    /**
     * POST /sessions (contract §5, task-2/5): cwd = the sheet's chosen workspace (explicit; the
     * host's workspace order is session-recency and must not decide the target). Optional rename
     * (task-5) via PATCH /sessions/{id}; a failed rename toasts but still opens the created
     * session. Single-flight via the disabled Create button; failures land in the sheet's error
     * row (typed 4xx copy).
     */
    private void doCreateSession(View fab, String name, String presetId, String cwd,
                                 AlertDialog dialog, Button createButton, TextView errorView,
                                 Runnable onPresetUnavailable) {
        if (fab != null) fab.setEnabled(false);
        DshLog.sessionCreate(cwd);
        DshLog.sessionCreatePreset(presetId);
        io.execute(() -> {
            try {
                if (AppConfig.DEBUG && debugCreateFailure != null) {
                    String seeded = debugCreateFailure;
                    debugCreateFailure = null; // single-shot fixture
                    throw new Exception(seeded);
                }
                JSONObject body = api.createSession(cwd, presetId);
                JSONObject json = body == null ? null : body.optJSONObject("session");
                if (json == null) throw new Exception("no session in response");
                Models.Session created = Models.Session.from(json);
                if (name != null && !name.isEmpty()) {
                    try {
                        api.renameSession(created.id, name);
                        created.title = name;
                        DshLog.marker("EVENT session.rename.ok sessionId=" + created.id);
                    } catch (Exception renameError) {
                        String renameReason = String.valueOf(renameError.getMessage());
                        DshLog.marker("EVENT session.rename.fail reason="
                                + renameReason.replace(' ', '_'));
                        runOnUiThread(() -> Toast.makeText(this,
                                getString(R.string.session_rename_failed, renameReason),
                                Toast.LENGTH_LONG).show());
                    }
                }
                runOnUiThread(() -> {
                    DshLog.sessionCreateOk(created.id);
                    if (data != null) data.sessions.add(0, created);
                    if (fab != null) fab.setEnabled(true);
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    Toast.makeText(this, R.string.session_created, Toast.LENGTH_SHORT).show();
                    openSession(created);
                });
            } catch (Exception error) {
                String reason = String.valueOf(error.getMessage());
                Log.w(TAG, "session create failed", error);
                runOnUiThread(() -> {
                    DshLog.sessionCreateFail(reason);
                    if (fab != null) fab.setEnabled(true);
                    if (createButton != null) {
                        createButton.setEnabled(true);
                        createButton.setText(R.string.session_create_label);
                    }
                    if (errorView != null) {
                        errorView.setText(createErrorCopy(reason));
                        errorView.setVisibility(View.VISIBLE);
                    }
                    // C2: a typed 409 agent-preset-unavailable triggers exactly one roster
                    // re-read while the sheet is still open (contract New-session flow step 4).
                    if (onPresetUnavailable != null
                            && "agent-preset-unavailable".equals(createErrorCode(reason))
                            && dialog != null && dialog.isShowing()) {
                        onPresetUnavailable.run();
                    }
                });
            }
        });
    }

    /** Parsed "HTTP <status>: <json>" create failure — shared by copy and code extraction. */
    private static final class CreateErrorInfo {
        int status;
        String code;
        String message;
        String bodyText;
    }

    private CreateErrorInfo parseCreateError(String raw) {
        CreateErrorInfo info = new CreateErrorInfo();
        info.bodyText = raw == null ? "" : raw;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^HTTP (\\d{3}):\\s*(.*)$", java.util.regex.Pattern.DOTALL)
                .matcher(info.bodyText);
        if (matcher.matches()) {
            info.status = Integer.parseInt(matcher.group(1));
            info.bodyText = matcher.group(2);
        }
        try {
            JSONObject error = new JSONObject(info.bodyText);
            // C3: v1ah sends the nested envelope {ok:false, error:{code,message,…}}; older/flat
            // shapes carry code/message at the top level. Read nested first, keep flat tolerance.
            JSONObject nested = error.optJSONObject("error");
            JSONObject envelope = nested == null ? error : nested;
            info.code = envelope.optString("code", null);
            info.message = envelope.optString("message", null);
        } catch (Exception ignored) {
            // Plain-text fence (401/403) or a non-JSON body — keep the raw text.
        }
        return info;
    }

    /** Typed error code from a create failure (e.g. "agent-preset-unavailable"), or null. */
    private String createErrorCode(String raw) {
        return parseCreateError(raw).code;
    }

    /**
     * Turn the ApiClient IOException ("HTTP 409: {code,message,...}") into one readable line.
     * Typed v1ah preset errors: unknown preset → 404, unavailable/broken preset → 409,
     * non-string/invalid body → 400. Fall back to the server message, then the raw text.
     */
    private String createErrorCopy(String raw) {
        CreateErrorInfo info = parseCreateError(raw);
        String detail = info.message != null && !info.message.isEmpty()
                ? info.message : info.code;
        if (info.status == 404) {
            return getString(R.string.session_create_preset_not_found);
        }
        if (info.status == 409) {
            return getString(R.string.session_create_preset_unavailable,
                    detail == null || detail.isEmpty() ? "try another preset" : detail);
        }
        if (info.status == 400 || info.status == 422) {
            return getString(R.string.session_create_rejected,
                    detail == null || detail.isEmpty() ? "check the name and preset" : detail);
        }
        return getString(R.string.session_create_failed,
                detail == null || detail.isEmpty() ? info.bodyText : detail);
    }

    /** R2: refresh the Chats list in place so stream frames don't reset scroll to top. */
    private void refreshChannelsInPlace() {
        if (tab != TAB_CHATS || chatOpen || showingDecisions) return;
        if (channelAdapter == null || channelListView == null || channelRowsLive == null) {
            showTab(TAB_CHATS);
            return;
        }
        int first = channelListView.getFirstVisiblePosition();
        View anchor = channelListView.getChildAt(0);
        int top = anchor == null ? 0 : anchor.getTop();
        List<Row> fresh = channelRows();
        channelRowsLive.clear();
        channelRowsLive.addAll(fresh);
        channelAdapter.notifyDataSetChanged();
        if (first > 0 || top != 0) channelListView.setSelectionFromTop(first, top);
        DshLog.marker("EVENT channels.refresh.inplace rows=" + fresh.size());
    }

    /** R3: decision.upsert frames update the local feed and the #decisions row badge. */
    private void handleDecisionUpsert(String payload) {
        try {
            Models.Decision decision = Models.Decision.from(new JSONObject(payload));
            if (decision.id != null && !decision.id.isEmpty()) {
                for (int i = decisionsFeed.size() - 1; i >= 0; i--) {
                    if (decision.id.equals(decisionsFeed.get(i).id)) decisionsFeed.remove(i);
                }
                if ("pending".equalsIgnoreCase(decision.state)) decisionsFeed.add(decision);
            }
        } catch (Exception error) {
            Log.w(TAG, "bad decision.upsert frame", error);
        }
        refreshDecisionsBadge();
    }

    /** R3: single-flight refresh of the waiting/urgent counters from the existing feed. */
    private void refreshDecisionsBadge() {
        if (decisionsBadgeRefreshing) return;
        decisionsBadgeRefreshing = true;
        io.execute(() -> {
            int waiting = decisionsWaiting;
            int urgent = decisionsUrgent;
            try {
                JSONObject body = api.decisions();
                JSONObject badge = body.optJSONObject("badge");
                waiting = badge == null ? 0 : badge.optInt("waiting", 0);
                urgent = badge == null ? 0 : badge.optInt("urgent", 0);
            } catch (Exception error) {
                Log.w(TAG, "decisions badge refresh failed", error);
            }
            final int nextWaiting = waiting;
            final int nextUrgent = urgent;
            runOnUiThread(() -> {
                decisionsBadgeRefreshing = false;
                decisionsWaiting = nextWaiting;
                decisionsUrgent = nextUrgent;
                // Badge retarget (UI overhaul): the #decisions row is gone from the Chats list,
                // so the waiting state surfaces on the rail Tools row (amber dot). The drawer's
                // Decisions caption recomputes on next open.
                updateRailToolsBadge();
                if (showingDecisions) {
                    renderDecisions();
                } else if (tab == TAB_CHATS && !chatOpen) {
                    refreshChannelsInPlace();
                }
            });
        });
    }

    /** R3: foreground poll so a waiting decision surfaces without opening #decisions. */
    private void scheduleDecisionsPoll() {
        if (decisionsPollRunnable != null) return;
        decisionsPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!foreground) return;
                refreshDecisionsBadge();
                streamHandler.postDelayed(this, 15000L);
            }
        };
        streamHandler.postDelayed(decisionsPollRunnable, 15000L);
    }

    private void stopDecisionsPoll() {
        if (decisionsPollRunnable != null) {
            streamHandler.removeCallbacks(decisionsPollRunnable);
            decisionsPollRunnable = null;
        }
    }

    private void openSession(Models.Session session) {
        showingDecisions = false;
        chatOpen = true;
        currentSession = session;
        DshLog.chatOpen(session.id, session.title == null ? "" : session.title);
        prefs.setLastRead(session.id, System.currentTimeMillis());
        // Persist the opened session's cwd — the sheet's second default (survives a cold start).
        prefs.setLastCwd(session.cwd);
        backButton.setVisibility(View.VISIBLE);
        topTitle.setText(session.title);
        topSubtitle.setText(session.cwd);
        Log.d(TAG, "openSession id=" + session.id);

        LinearLayout root = new LinearLayout(this);
        root.setId(R.id.chat_screen);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        FrameLayout frame = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setId(R.id.chat_list);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setBackgroundColor(getColor(R.color.bg));
        TextView empty = emptyView(getString(R.string.chat_empty_title), getString(R.string.chat_empty_body));
        empty.setId(R.id.chat_empty);
        transcriptLoadingOlder = new TextView(this);
        transcriptLoadingOlder.setId(R.id.chat_loading_older);
        transcriptLoadingOlder.setText(R.string.loading_older);
        transcriptLoadingOlder.setTextColor(getColor(R.color.faint));
        transcriptLoadingOlder.setTextSize(11);
        transcriptLoadingOlder.setGravity(Gravity.CENTER);
        transcriptLoadingOlder.setPadding(0, dp(10), 0, dp(10));
        transcriptLoadingOlder.setVisibility(View.GONE);
        list.addHeaderView(transcriptLoadingOlder, null, false);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
                if (firstVisibleItem <= 1 && !loadingOlder && hasOlder && chatOpen
                        && currentSession != null) {
                    loadOlder(currentSession);
                }
            }
        });
        frame.addView(list);
        frame.addView(empty);
        list.setEmptyView(empty);
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackgroundColor(getColor(R.color.bg_rail));
        composer.setPadding(dp(12), dp(8), dp(12), dp(8));
        EditText input = new EditText(this);
        input.setId(R.id.composer_input);
        input.setContentDescription(getString(AppConfig.M1_READ_ONLY
                ? R.string.composer_hint : R.string.composer_placeholder));
        input.setHint(AppConfig.M1_READ_ONLY ? R.string.composer_hint : R.string.composer_placeholder);
        input.setTextSize(14);
        input.setEnabled(!AppConfig.M1_READ_ONLY);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.faint));
        composer.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button send = new Button(this);
        send.setId(R.id.composer_send);
        send.setContentDescription(getString(R.string.send));
        send.setText(R.string.send);
        send.setAllCaps(false);
        send.setTextSize(13);
        send.setTextColor(getColor(R.color.on_accent));
        send.setBackgroundResource(R.drawable.bg_button);
        send.setMinWidth(0);
        send.setMinimumWidth(0);
        send.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(68), dp(44));
        sendParams.leftMargin = dp(8);
        composer.addView(send, sendParams);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean sendAction = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (sendAction) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                sendMessage(session, input, send, list, empty);
                return true;
            }
            return false;
        });
        if (AppConfig.M1_READ_ONLY) {
            send.setEnabled(false);
            send.setAlpha(0.5f);
        } else {
            send.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                sendMessage(session, input, send, list, empty);
            });
        }
        jumpLatest = new TextView(this);
        jumpLatest.setId(R.id.chat_jump_latest);
        jumpLatest.setText(R.string.jump_to_latest);
        jumpLatest.setTextColor(getColor(R.color.on_accent));
        jumpLatest.setTextSize(12);
        jumpLatest.setGravity(Gravity.CENTER);
        jumpLatest.setPadding(dp(14), dp(8), dp(14), dp(8));
        jumpLatest.setBackgroundResource(R.drawable.bg_badge);
        jumpLatest.setVisibility(View.GONE);
        jumpLatest.setContentDescription(getString(R.string.jump_to_latest));
        jumpLatest.setOnClickListener(v -> {
            unseenLive = 0;
            jumpLatest.setVisibility(View.GONE);
            if (transcriptList != null && transcriptAdapter != null) {
                transcriptList.setSelection(transcriptAdapter.getCount() - 1);
            }
        });
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.gravity = Gravity.CENTER_HORIZONTAL;
        pillParams.bottomMargin = dp(6);
        composerInput = input;
        composerSend = send;
        root.addView(jumpLatest, pillParams);
        root.addView(composer);
        content.removeAllViews();
        content.addView(root);
        chatEmptyView = empty;
        pendingSends.clear();
        sendOkEmitted.clear();
        transcriptLoading = true;
        transcript = new ArrayList<>();
        transcriptAdapter = null;
        pendingStreamMessages.clear();
        throughSeq = throughSeqBySession.containsKey(session.id)
                ? throughSeqBySession.get(session.id) : 0L;
        loadMessages(session, list, empty, 0);
    }

    private void loadMessages(Models.Session session, ListView list, TextView empty, int attempt) {
        final String expectedSession = session.id;
        transcriptList = list;
        chatEmptyView = empty;
        transcriptLoading = true;
        io.execute(() -> {
            try {
                JSONObject body = api.messages(session.id, 50);
                JSONArray items = body.optJSONArray("items");
                List<Models.Message> messages = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject row = items.optJSONObject(i);
                        if (row != null) messages.add(Models.Message.from(row));
                    }
                }
                final long pageThrough = body.optLong("throughSeq", 0);
                Log.d(TAG, "messages session=" + session.id + " n=" + messages.size());
                runOnUiThread(() -> {
                    if (currentSession == null || !expectedSession.equals(currentSession.id)) {
                        DshLog.streamDrop(expectedSession, "stale-transcript");
                        transcriptLoading = false;
                        return;
                    }
                    throughSeqBySession.put(expectedSession, pageThrough);
                    throughSeq = pageThrough;
                    transcript = messages;
                    // Reconcile optimistic rows: drop pendings the page already contains.
                    for (int i = pendingSends.size() - 1; i >= 0; i--) {
                        Models.Message pending = pendingSends.get(i);
                        Models.Message durableMessage = null;
                        for (Models.Message pageMessage : transcript) {
                            if (pageMessage.clientRequestId != null
                                    && pageMessage.clientRequestId.equals(pending.clientRequestId)) {
                                durableMessage = pageMessage;
                                break;
                            }
                            if ("user".equals(pageMessage.role) && pending.text != null
                                    && pending.text.equals(pageMessage.text)) {
                                // Refetched pages may omit clientRequestId (G1 echo is not always in
                                // the projection) — fall back to text+role matching.
                                durableMessage = pageMessage;
                            }
                        }
                        if (durableMessage != null) {
                            transcript.remove(pending);
                            pendingSends.remove(i);
                            emitSendReconciled(
                                    pending.clientRequestId != null ? pending.clientRequestId : "",
                                    durableMessage.clientRequestId != null ? "id" : "text",
                                    durableMessage.seq,
                                    pending.clientRequestId != null
                                            ? pending.clientRequestId : "text:" + pending.text);
                        }
                    }
                    transcript.addAll(pendingSends);
                    java.util.Collections.sort(transcript, MainActivity::compareSeq);
                    // §4.5: apply buffered stream frames newer than the page's throughSeq.
                    for (Models.Message pending : pendingStreamMessages.values()) {
                        if (pending.seq <= throughSeq) continue;
                        boolean exists = false;
                        for (Models.Message current : transcript) {
                            if (current.seq == pending.seq) { exists = true; break; }
                        }
                        if (!exists) transcript.add(pending);
                    }
                    pendingStreamMessages.clear();
                    java.util.Collections.sort(transcript, MainActivity::compareSeq);
                    transcriptLoading = false;
                    hasOlder = messages.size() >= 50;
                    oldestSeq = messages.isEmpty() ? 0 : messages.get(0).seq;
                    transcriptAdapter = new MessageAdapter(session.id, transcript);
                    list.setAdapter(transcriptAdapter);
                    if (!messages.isEmpty()) list.setSelection(messages.size() - 1);
                    long first = messages.isEmpty() ? 0 : messages.get(0).seq;
                    long last = messages.isEmpty() ? 0 : messages.get(messages.size() - 1).seq;
                    DshLog.sessionPage(session.id, "none", messages.size(), hasOlder);
                    DshLog.transcriptRender(session.id, first, last, messages.size());
                });
            } catch (Exception error) {
                Log.w(TAG, "messages failed", error);
                runOnUiThread(() -> empty.setText(getString(R.string.connect_error_body)));
            }
        });
    }

    /** AC-4: load older page at scroll-top, preserve the anchor, dedupe by seq. */
    private void loadOlder(Models.Session session) {
        if (loadingOlder || !hasOlder || transcriptList == null || transcript.isEmpty()) return;
        loadingOlder = true;
        final long before = oldestSeq;
        if (transcriptLoadingOlder != null) transcriptLoadingOlder.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONObject body = api.messages(session.id, before, 50);
                JSONArray items = body.optJSONArray("items");
                List<Models.Message> older = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject row = items.optJSONObject(i);
                        if (row != null) older.add(Models.Message.from(row));
                    }
                }
                runOnUiThread(() -> {
                    int added = 0;
                    int top = 0;
                    if (transcriptList.getFirstVisiblePosition() == 0
                            && transcriptList.getChildCount() > 0) {
                        top = -transcriptList.getChildAt(0).getTop();
                    }
                    for (Models.Message message : older) {
                        boolean duplicate = false;
                        for (Models.Message existing : transcript) {
                            if (existing.seq == message.seq) { duplicate = true; break; }
                        }
                        if (!duplicate) { transcript.add(0, message); added++; }
                    }
                    if (added > 0) {
                        if (transcriptAdapter != null) transcriptAdapter.notifyDataSetChanged();
                        transcriptList.setSelectionFromTop(added, top);
                        oldestSeq = transcript.get(0).seq;
                    }
                    // Stop paging when the page added nothing (host ignored before=, or start reached).
                    hasOlder = added > 0 && older.size() >= 50;
                    DshLog.sessionPage(session.id, String.valueOf(before), older.size(), hasOlder);
                    long first = transcript.isEmpty() ? 0 : transcript.get(0).seq;
                    long last = transcript.isEmpty() ? 0 : transcript.get(transcript.size() - 1).seq;
                    DshLog.transcriptRender(session.id, first, last, transcript.size());
                    if (transcriptLoadingOlder != null) transcriptLoadingOlder.setVisibility(View.GONE);
                    loadingOlder = false;
                });
            } catch (Exception error) {
                Log.w(TAG, "older page failed", error);
                runOnUiThread(() -> {
                    if (transcriptLoadingOlder != null) transcriptLoadingOlder.setVisibility(View.GONE);
                    loadingOlder = false;
                    hasOlder = false;
                });
            }
        });
    }

    private void sendMessage(Models.Session session, EditText input, Button send, ListView list,
                             TextView empty) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        final String requestId = UUID.randomUUID().toString();
        Models.Message pending = new Models.Message();
        pending.role = "user";
        pending.kind = "message";
        pending.text = text;
        pending.status = "sending";
        pending.clientRequestId = requestId;
        pending.seq = 0;
        pendingSends.add(pending);
        transcript.add(pending);
        if (transcriptAdapter != null) {
            transcriptAdapter.setStreamText(null);
            transcriptAdapter.notifyDataSetChanged();
        }
        if (list != null) list.setSelection(transcript.size() - 1);
        input.setText("");
        DshLog.sendPending(requestId, session.id);
        DshLog.sendStart(requestId, session.id, text.length());
        sendRequest(session, text, requestId, input, send, list);
    }

    /** POST /send with the client-minted requestId; retries reuse the same id (idempotent). */
    private void sendRequest(Models.Session session, String text, String requestId,
                             EditText input, Button send, ListView list) {
        if (session == null) return;
        if (send != null) send.setEnabled(false);
        io.execute(() -> {
            try {
                api.send(session.id, text, requestId);
                runOnUiThread(() -> {
                    if (send != null) send.setEnabled(true);
                    // send.ok is emitted once, on the durable echo, never on the POST ack.
                    // M3 marker determinism: lightweight transcript refetch so loadMessages reconciliation
                    // always sees the durable user row even when the stream doesn't deliver it.
                    streamHandler.postDelayed(() -> {
                        if (chatOpen && currentSession != null
                                && currentSession.id.equals(session.id)
                                && transcriptList != null) {
                            loadMessages(currentSession, transcriptList, chatEmptyView, 0);
                        }
                    }, 1500L);
                });
            } catch (Exception error) {
                String reason = String.valueOf(error.getMessage());
                runOnUiThread(() -> {
                    if (send != null) send.setEnabled(true);
                    Models.Message pending = findPending(requestId);
                    if (pending != null) pending.status = "failed";
                    if (transcriptAdapter != null) transcriptAdapter.notifyDataSetChanged();
                    DshLog.sendFail(requestId, reason);
                    if (input != null && input.getText().length() == 0) input.setText(text);
                    Toast.makeText(MainActivity.this,
                            "Send failed — tap the message to retry", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Models.Message findPending(String requestId) {
        if (requestId == null) return null;
        for (Models.Message pending : pendingSends) {
            if (requestId.equals(pending.clientRequestId)) return pending;
        }
        return null;
    }

    private Models.Message findPendingByText(String text) {
        for (Models.Message pending : pendingSends) {
            if (text != null && text.equals(pending.text)) return pending;
        }
        return null;
    }

    /** Single-fire send.echo/send.ok for both the stream echo and the page-refetch reconciliation. */
    private void emitSendReconciled(String markerId, String match, long seq, String dedupeKey) {
        String key = (dedupeKey == null || dedupeKey.isEmpty()) ? "seq:" + seq : dedupeKey;
        if (!sendOkEmitted.add(key)) return;
        DshLog.sendEcho(markerId, match, seq);
        DshLog.sendOk(markerId, seq);
    }

    /** Replace the optimistic row with the durable echo (by requestId, else by text). */
    private void reconcileDurable(Models.Message durable) {
        String rid = durable.clientRequestId;
        String text = durable.text;
        Models.Message markerPending = rid == null ? findPendingByText(text) : findPending(rid);
        boolean cleaned = false;
        // BUG-304: remove *every* optimistic row for this request (sending or failed), not just the
        // first one the pending map points at. Retry attempts must not leave a FAILED row behind.
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Models.Message message = transcript.get(i);
            boolean optimistic = "sending".equals(message.status) || "failed".equals(message.status);
            boolean same = (rid != null && rid.equals(message.clientRequestId))
                    || (rid == null && text != null && text.equals(message.text) && message.seq == 0);
            if (optimistic && same) {
                transcript.remove(i);
                cleaned = true;
            }
        }
        for (int i = pendingSends.size() - 1; i >= 0; i--) {
            Models.Message pending = pendingSends.get(i);
            boolean same = (rid != null && rid.equals(pending.clientRequestId))
                    || (rid == null && text != null && text.equals(pending.text));
            if (same) {
                pendingSends.remove(i);
                cleaned = true;
            }
        }
        String echoId = rid != null ? rid
                : (markerPending != null && markerPending.clientRequestId != null
                    ? markerPending.clientRequestId : "");
        // A page refetch may already contain the durable row; never duplicate it.
        for (Models.Message message : transcript) {
            if (message.seq > 0 && message.seq == durable.seq) {
                if (transcriptAdapter != null) transcriptAdapter.notifyDataSetChanged();
                emitSendReconciled(echoId, rid != null ? "id" : "text", durable.seq,
                        rid != null ? rid : "text:" + text);
                return;
            }
        }
        durable.status = "complete";
        transcript.add(durable);
        java.util.Collections.sort(transcript, MainActivity::compareSeq);
        if (transcriptAdapter != null) transcriptAdapter.notifyDataSetChanged();
        if (transcriptList != null) transcriptList.setSelection(transcript.size() - 1);
        emitSendReconciled(echoId, rid != null ? "id" : "text", durable.seq,
                rid != null ? rid : "text:" + text);
    }

    private void retrySend(Models.Message pending) {
        if (pending == null || !"failed".equals(pending.status)) return;
        // Collapse any duplicate optimistic rows for this request before retrying.
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Models.Message message = transcript.get(i);
            if (message != pending && ("sending".equals(message.status) || "failed".equals(message.status))
                    && message.clientRequestId != null
                    && message.clientRequestId.equals(pending.clientRequestId)) {
                transcript.remove(i);
            }
        }
        if (!transcript.contains(pending)) transcript.add(pending);
        pending.status = "sending";
        if (transcriptAdapter != null) transcriptAdapter.notifyDataSetChanged();
        DshLog.sendRetry(pending.clientRequestId);
        sendRequest(currentSession, pending.text, pending.clientRequestId, composerInput,
                composerSend, transcriptList);
    }

    private static int compareSeq(Models.Message a, Models.Message b) {
        long left = a.seq > 0 ? a.seq : Long.MAX_VALUE;
        long right = b.seq > 0 ? b.seq : Long.MAX_VALUE;
        return Long.compare(left, right);
    }

    private void togglePlugin(Card card, boolean enabled) {
        Log.d(TAG, "togglePlugin " + card.pluginEntryId + "=" + enabled);
        if (!enabled && card.pluginEntryId != null
                && (card.pluginEntryId.contains("session-watch") || card.pluginEntryId.contains("decisions"))) {
            showDependencyWarning(card, enabled);
            return;
        }
        performToggle(card, enabled);
    }

    private void showDependencyWarning(Card card, boolean enabled) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dependency_warning_title)
                .setMessage(R.string.dependency_warning_body)
                .setNegativeButton(R.string.dependency_cancel_label, null)
                .setPositiveButton(R.string.dependency_disable_confirm,
                        (dialog, which) -> performToggle(card, enabled))
                .show();
    }

    private void performToggle(Card card, boolean enabled) {
        Log.d(TAG, "performToggle " + card.pluginEntryId + "=" + enabled);
        DshLog.pluginToggle(card.pluginEntryId, enabled, "request");
        io.execute(() -> {
            try {
                JSONObject response = api.setPluginEnabled(card.pluginEntryId, enabled);
                boolean restart = response.optBoolean("restartRequired", false);
                runOnUiThread(() -> {
                    card.pluginEnabled = enabled;
                    pluginRestartRequired = pluginRestartRequired || restart;
                    DshLog.pluginToggle(card.pluginEntryId, enabled, "ok");
                    Toast.makeText(this, card.title + (enabled ? " enabled" : " disabled"),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                Log.w(TAG, "toggle failed", error);
                DshLog.pluginToggle(card.pluginEntryId, enabled, "fail");
                runOnUiThread(() -> {
                    Toast.makeText(this, "Toggle failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    showTab(TAB_PLUGINS);
                });
            }
        });
    }

    private void showJobDialog(Card card) {
        io.execute(() -> {
            try {
                JSONObject body = api.jobOutput(card.jobId, card.ownerSessionId);
                JSONArray chunks = body.optJSONArray("chunks");
                StringBuilder text = new StringBuilder();
                if (chunks != null) {
                    for (int i = 0; i < chunks.length(); i++) {
                        JSONObject chunk = chunks.optJSONObject(i);
                        if (chunk != null) text.append(chunk.optString("text", ""));
                    }
                }
                String output = text.length() == 0 ? "(no output yet)" : text.toString();
                if (output.length() > 4000) output = output.substring(0, 4000) + "…";
                final String shown = output;
                runOnUiThread(() -> showJobOutputDialog(card, shown));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "Output failed: " + error.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** R9: job output is monospace + selectable, with Copy alongside Kill/Close. */
    private void showJobOutputDialog(Card card, String output) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        TextView text = new TextView(this);
        text.setText(output);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setTextColor(getColor(R.color.text_soft));
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(text);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(card.title)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton(R.string.copy, (dialog, which) -> copyToClipboard("job output", output));
        if (!AppConfig.M1_READ_ONLY) {
            builder.setNegativeButton("Kill", (dialog, which) -> killJob(card));
        }
        builder.show();
    }

    private void killJob(Card card) {
        io.execute(() -> {
            try {
                api.killJob(card.jobId, card.ownerSessionId);
                runOnUiThread(() -> Toast.makeText(this, R.string.job_cancelled_by_user, Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "Kill failed: " + error.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---------------------------------------------------------------- tabs

    private static final class Card {
        final int kind;
        final String title;
        final String status;
        final String subtitle;
        final String body;
        final String tag;
        String jobId;
        String ownerSessionId;
        String pluginEntryId;
        boolean pluginEnabled;
        boolean pluginTogglable;
        String pluginReadOnlyReason;
        boolean decisionUrgent;
        String decisionRecommendation;

        Card(int kind, String title, String status, String subtitle, String body, String tag) {
            this.kind = kind;
            this.title = title;
            this.status = status;
            this.subtitle = subtitle;
            this.body = body;
            this.tag = tag;
        }
    }

    private void showCards(List<Card> cards, int emptyTitle, int emptyBody, int screenId,
                           int emptyId) {
        FrameLayout frame = new FrameLayout(this);
        frame.setId(screenId);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(getColor(R.color.bg));
        TextView empty = emptyView(getString(emptyTitle), getString(emptyBody));
        empty.setId(emptyId);
        frame.addView(list);
        frame.addView(empty);
        list.setEmptyView(empty);
        list.setAdapter(new CardAdapter(cards));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Card card = cards.get(position);
            if (card.jobId != null) showJobDialog(card);
        });
        content.removeAllViews();
        content.addView(frame);
    }

    private void showAgents() {
        List<Card> cards = new ArrayList<>();
        for (Models.Agent agent : roster()) {
            cards.add(new Card(CARD_AGENT, agentDisplayName(agent), agent.status,
                    agent.role + (agent.model == null ? "" : " · " + agent.model),
                    agent.description, agent.id));
        }
        showCards(cards, R.string.agents_empty_title, R.string.agents_empty_body,
                R.id.agents_screen, R.id.agents_empty);
    }

    private void showPlugins() {
        List<Card> extensions = new ArrayList<>();
        List<Card> core = new ArrayList<>();
        if (data != null) {
            for (Models.PluginCard plugin : data.plugins) {
                if (!query.isEmpty()) {
                    String haystack = (plugin.title + " " + plugin.module).toLowerCase();
                    if (!haystack.contains(query)) continue;
                }
                String status = plugin.enabled
                        ? (plugin.phase.isEmpty() ? getString(R.string.enabled) : plugin.phase)
                        : getString(R.string.disabled);
                Card card = new Card(CARD_PLUGIN, plugin.title, status, plugin.module,
                        plugin.description, plugin.entryId);
                card.pluginEntryId = plugin.entryId;
                card.pluginEnabled = plugin.enabled;
                card.pluginTogglable = plugin.togglable;
                card.pluginReadOnlyReason = plugin.readOnlyReason;
                // Acceptance (task-20): the 11 dsh-plugin-* modules are the extensions; everything
                // else is a core/loader entry and lives in its own section.
                boolean extension = plugin.module != null && plugin.module.startsWith("dsh-plugin-");
                (extension ? extensions : core).add(card);
            }
        }
        java.util.Collections.sort(extensions, (a, b) -> a.title.compareToIgnoreCase(b.title));
        List<Card> cards = new ArrayList<>();
        if (pluginRestartRequired) {
            cards.add(new Card(CARD_SECTION, getString(R.string.plugin_restart_required),
                    "", "", "", ""));
        }
        if (!extensions.isEmpty()) {
            cards.add(new Card(CARD_SECTION, "EXTENSIONS · " + extensions.size(), "", "", "", ""));
            cards.addAll(extensions);
            if (!core.isEmpty()) {
                // Core/loader entries stay hidden in M2 (task-20 acceptance: hidden or separate).
                cards.add(new Card(CARD_SECTION, "CORE · " + core.size() + " hidden", "", "", "", ""));
            }
        } else {
            cards.addAll(core);
        }
        DshLog.pluginsList(cards.size(), extensions.size(), core.size());
        showCards(cards, R.string.plugins_empty_title, R.string.plugins_empty_body,
                R.id.plugins_screen, R.id.plugins_empty);
    }

    private void showJobs() {
        List<Card> cards = new ArrayList<>();
        if (data != null) {
            int limit = Math.min(data.jobs.size(), 80);
            for (int i = 0; i < limit; i++) {
                Models.Job job = data.jobs.get(i);
                String sub = job.kind;
                if (!job.sessionTitle.isEmpty()) sub = sub + " · " + job.sessionTitle;
                if (job.startedAt > 0) sub = sub + " · " + elapsed(job);
                String body = job.progress;
                if (job.detail != null && !job.detail.isEmpty()) {
                    body = body.isEmpty() ? job.detail : body + " · " + job.detail;
                }
                Card card = new Card(CARD_JOB, job.label, job.status, sub, body, job.id);
                card.jobId = job.id;
                card.ownerSessionId = (job.sessionId != null && !job.sessionId.isEmpty())
                        ? job.sessionId : job.ownerSessionId;
                cards.add(card);
            }
        }
        showCards(cards, R.string.jobs_empty_title, R.string.jobs_empty_body,
                R.id.jobs_screen, R.id.jobs_empty);
    }

    private void showDecisions() {
        showingDecisions = true;
        chatOpen = false;
        currentSession = null;
        backButton.setVisibility(View.VISIBLE);
        backButton.setContentDescription(getString(R.string.back));
        topTitle.setText(R.string.decisions_title);
        topSubtitle.setText(R.string.decisions_subtitle_live);
        renderDecisionsLoading();
        loadDecisionsFeed();
    }

    private void renderDecisionsLoading() {
        FrameLayout frame = new FrameLayout(this);
        frame.setId(R.id.decisions_screen);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView badge = new TextView(this);
        badge.setId(R.id.decisions_badge);
        badge.setText("Waiting: " + decisionsWaiting);
        badge.setTextColor(getColor(R.color.muted));
        badge.setTextSize(12);
        badge.setPadding(dp(14), dp(10), dp(14), dp(10));
        column.addView(badge);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(28), dp(28),
                Gravity.CENTER);
        frame.addView(column);
        frame.addView(spinner, params);
        content.removeAllViews();
        content.addView(frame);
    }

    private void loadDecisionsFeed() {
        if (decisionsLoading) return;
        decisionsLoading = true;
        io.execute(() -> {
            try {
                JSONObject body = api.decisions();
                List<Models.Decision> items = new ArrayList<>();
                JSONArray array = body.optJSONArray("items");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject row = array.optJSONObject(i);
                        if (row != null) items.add(Models.Decision.from(row));
                    }
                }
                JSONObject badge = body.optJSONObject("badge");
                int waiting = badge == null ? 0 : badge.optInt("waiting", 0);
                int urgent = badge == null ? 0 : badge.optInt("urgent", 0);
                String sourceProblem = body.isNull("sourceProblem")
                        ? null : body.optString("sourceProblem", null);
                boolean answerable = body.optBoolean("answerable", false);
                runOnUiThread(() -> {
                    decisionsFeed = items;
                    decisionsWaiting = waiting;
                    decisionsUrgent = urgent;
                    decisionsSourceProblem = sourceProblem;
                    decisionsAnswerable = answerable;
                    decisionsLoading = false;
                    renderDecisions();
                    if (sourceProblem != null) DshLog.decisionsSource(sourceProblem);
                });
            } catch (Exception error) {
                Log.w(TAG, "decisions feed failed", error);
                runOnUiThread(() -> {
                    decisionsLoading = false;
                    if (data != null && !data.decisions.isEmpty()) decisionsFeed = data.decisions;
                    decisionsSourceProblem = "unavailable";
                    renderDecisions();
                    DshLog.decisionsSource("unavailable");
                });
            }
        });
    }

    /** Dedupe by decisionId; running-turn blockers first, then urgent, then oldest. */
    private List<Models.Decision> orderedDecisions() {
        java.util.LinkedHashMap<String, Models.Decision> unique = new java.util.LinkedHashMap<>();
        for (Models.Decision decision : decisionsFeed) {
            if (decision.id == null || decision.id.isEmpty()) continue;
            if (decisionDismissed.contains(decision.id)) continue;
            unique.put(decision.id, decision);
        }
        List<Models.Decision> ordered = new ArrayList<>(unique.values());
        java.util.Collections.sort(ordered, (a, b) -> {
            if (a.running != b.running) return a.running ? -1 : 1;
            if (a.urgent != b.urgent) return a.urgent ? -1 : 1;
            return Long.compare(b.waitedMs, a.waitedMs);
        });
        return ordered;
    }

    private void renderDecisions() {
        List<Models.Decision> ordered = orderedDecisions();
        FrameLayout frame = new FrameLayout(this);
        frame.setId(R.id.decisions_screen);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView badge = new TextView(this);
        badge.setId(R.id.decisions_badge);
        badge.setText("Waiting: " + decisionsWaiting
                + (decisionsUrgent > 0 ? " · urgent: " + decisionsUrgent : ""));
        badge.setTextColor(getColor(decisionsUrgent > 0 ? R.color.amber : R.color.muted));
        badge.setTextSize(12);
        badge.setPadding(dp(14), dp(10), dp(14), dp(10));
        column.addView(badge);
        if (decisionsSourceProblem != null) {
            LinearLayout problem = new LinearLayout(this);
            problem.setId(R.id.decision_source_problem);
            problem.setOrientation(LinearLayout.VERTICAL);
            problem.setBackgroundResource(R.drawable.bg_card);
            problem.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView title = UiText.bold(this, getString(R.string.decision_source_problem));
            title.setTextColor(getColor(R.color.amber));
            problem.addView(title);
            TextView hint = UiText.small(this, decisionsSourceProblem
                    + " · " + getString(R.string.decision_source_problem_hint));
            problem.addView(hint);
            LinearLayout.LayoutParams problemParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            problemParams.setMargins(dp(10), dp(4), dp(10), dp(6));
            column.addView(problem, problemParams);
        }
        FrameLayout listFrame = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(getColor(R.color.bg));
        TextView empty = emptyView(getString(R.string.decisions_empty_title),
                getString(R.string.decisions_empty_body));
        empty.setId(R.id.decisions_empty);
        listFrame.addView(list);
        listFrame.addView(empty);
        list.setEmptyView(empty);
        list.setAdapter(new DecisionAdapter(ordered));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Models.Decision decision = ordered.get(position);
            DshLog.decisionOpen(decision.id, "inbox");
            Toast.makeText(this, decision.title + " · " + decision.state, Toast.LENGTH_SHORT).show();
        });
        column.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        frame.addView(column);
        content.removeAllViews();
        content.addView(frame);
        DshLog.decisionsLoad(decisionsSourceProblem == null ? "live" : "problem",
                decisionsWaiting, decisionsUrgent);
    }

    /** Read-only card for inbox and inline transcript surfaces (one object, one state). */
    private View decisionCardView(Models.Decision decision, boolean inline) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (inline) {
            card.setId(R.id.chat_decision_card);
            card.setContentDescription("decision:" + decision.id);
        } else {
            card.setId(R.id.decision_row);
            card.setTag(decision.id);
            card.setContentDescription("decision_row:" + decision.id);
        }
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(10), dp(4), dp(10), dp(6));
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView kind = UiText.bold(this, decision.kind == null ? "decision"
                : decision.kind.toUpperCase(java.util.Locale.US));
        kind.setId(R.id.decision_kind);
        kind.setTextColor(getColor(R.color.purple));
        header.addView(kind);
        if (decision.urgent) {
            TextView urgent = UiText.small(this, "URGENT");
            urgent.setId(R.id.decision_urgent);
            urgent.setTextColor(getColor(R.color.amber));
            LinearLayout.LayoutParams urgentParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            urgentParams.leftMargin = dp(8);
            header.addView(urgent, urgentParams);
        }
        TextView age = UiText.small(this, waited(decision.waitedMs));
        age.setId(R.id.decision_age);
        LinearLayout.LayoutParams ageParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ageParams.leftMargin = dp(8);
        header.addView(age, ageParams);
        card.addView(header);

        TextView title = UiText.bold(this, decision.title);
        title.setId(R.id.decision_question);
        card.addView(title);
        if (decision.prompt != null && !decision.prompt.isEmpty()) {
            TextView prompt = UiText.small(this, decision.prompt);
            prompt.setId(R.id.decision_prompt);
            card.addView(prompt);
        }
        if (decision.detail != null && !decision.detail.isEmpty()) {
            TextView detail = UiText.small(this, decision.detail);
            detail.setId(R.id.decision_detail);
            detail.setTextColor(getColor(R.color.muted));
            card.addView(detail);
        }
        if (decision.recommendation != null && !decision.recommendation.isEmpty()) {
            TextView rec = UiText.small(this, "Recommended: " + decision.recommendation);
            rec.setId(R.id.decision_recommendation);
            rec.setTextColor(getColor(R.color.accent));
            card.addView(rec);
        }
        if (!decision.options.isEmpty()) {
            TextView options = UiText.small(this, "Options: "
                    + TextUtils.join(" · ", decision.options));
            card.addView(options);
        }
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView session = UiText.small(this, decision.sessionTitle == null
                ? getString(R.string.session_untitled) : decision.sessionTitle);
        session.setId(R.id.decision_session);
        meta.addView(session, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView workspace = UiText.small(this, decision.workspacePath == null
                ? getString(R.string.decision_workspace_unknown) : decision.workspacePath);
        workspace.setId(R.id.decision_workspace);
        workspace.setTextColor(getColor(R.color.faint));
        meta.addView(workspace);
        card.addView(meta);
        if (decision.toolName != null && !decision.toolName.isEmpty()) {
            TextView tool = UiText.small(this, decision.toolName);
            tool.setId(R.id.decision_tool);
            tool.setTextColor(getColor(R.color.faint));
            card.addView(tool);
        }
        String stateValue = effectiveDecisionState(decision);
        TextView state = UiText.small(this, stateLabel(decision, stateValue));
        state.setId(R.id.decision_state);
        state.setTextColor(getColor(decision.urgent ? R.color.amber : R.color.muted));
        card.addView(state);
        addDecisionActions(card, decision, stateValue, inline);
        if (decision.sessionId != null && !decision.sessionId.isEmpty()) {
            TextView openSession = UiText.small(this, getString(R.string.decision_open_session));
            openSession.setId(R.id.decision_open_session);
            openSession.setTextColor(getColor(R.color.accent));
            openSession.setOnClickListener(v -> openDecisionSession(decision));
            card.addView(openSession);
        }
        if (inline) {
            TextView open = UiText.small(this, getString(R.string.decision_open_in_inbox));
            open.setTextColor(getColor(R.color.accent));
            open.setOnClickListener(v -> showDecisions());
            card.addView(open);
        }
        card.setOnClickListener(v -> {
            DshLog.decisionOpen(decision.id, inline ? "inline" : "inbox");
            Toast.makeText(this, decision.title + " · " + decision.state, Toast.LENGTH_SHORT).show();
        });
        return card;
    }

    /** R8: jump from a decision card to the session that owns it (context before allowing). */
    private void openDecisionSession(Models.Decision decision) {
        DshLog.decisionOpen(decision.id, "card-jump");
        openSessionById(decision.sessionId);
    }

    /** Open a session by id; re-bootstrap once when it is not in the current list (R8/R9). */
    private void openSessionById(String sessionId) {
        Models.Session target = findSession(sessionId);
        if (target != null) {
            openSession(target);
            return;
        }
        pendingOpenSessionId = sessionId;
        Toast.makeText(this, R.string.connect_working, Toast.LENGTH_SHORT).show();
        load();
    }

    /** R5: long-press a session row → mark read/unread or copy its id. */
    private void showSessionMenu(Models.Session session) {
        boolean unread = session.updatedAt > prefs.lastRead(session.id);
        String[] actions = {
                getString(unread ? R.string.mark_read : R.string.mark_unread),
                getString(R.string.copy_session_id),
        };
        new AlertDialog.Builder(this)
                .setTitle(session.title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        prefs.setLastRead(session.id, unread ? System.currentTimeMillis() : 0L);
                        DshLog.unreadCompute(session.id,
                                session.updatedAt > prefs.lastRead(session.id));
                        refreshChannelsInPlace();
                    } else {
                        copyToClipboard("sessionId", session.id);
                    }
                })
                .show();
    }

    private void copyToClipboard(String label, String value) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private Models.Session findSession(String sessionId) {
        if (data == null || sessionId == null || sessionId.isEmpty()) return null;
        for (Models.Session session : data.sessions) {
            if (sessionId.equals(session.id)) return session;
        }
        return null;
    }

    private String effectiveDecisionState(Models.Decision decision) {
        String local = decisionLocalState.get(decision.id);
        if (local != null) return local;
        return decision.state == null ? "pending" : decision.state;
    }

    private boolean canAnswerDecisions() {
        return data != null && (data.decisionAnswer || decisionsAnswerable);
    }

    private boolean decisionAnswerableState(String state) {
        return "pending".equals(state) || "continued".equals(state)
                || "expired-answerable".equals(state);
    }

    private String stateLabel(Models.Decision decision, String state) {
        if ("withdrawn".equals(state)) return getString(R.string.decision_withdrawn);
        if ("expired".equals(state)) return getString(R.string.decision_expired);
        if ("stale".equals(state)) return getString(R.string.decision_stale);
        if ("continued".equals(state)) return getString(R.string.decision_continued);
        if ("answered".equals(state)) return getString(R.string.decision_answered_elsewhere);
        if ("submitting".equals(state)) return "Submitting...";
        if ("failed".equals(state)) return "Answer failed - Retry";
        if ("reject-pending".equals(state)) return getString(R.string.approval_rejecting);
        if ("expired-answerable".equals(state)) return getString(R.string.decision_late_answer);
        if ("pending".equals(state)) return "Pending - answer below";
        return state;
    }

    private static String waited(long waitedMs) {
        if (waitedMs <= 0) return "";
        if (waitedMs < 60_000L) return "waiting now";
        if (waitedMs < 3_600_000L) return "waiting " + (waitedMs / 60_000L) + "m";
        return "waiting " + (waitedMs / 3_600_000L) + "h";
    }

    private void addDecisionActions(LinearLayout card, Models.Decision decision, String state,
                                    boolean inline) {
        if (decisionSubmitting.contains(decision.id)) {
            TextView submitting = UiText.small(this, "Submitting...");
            submitting.setId(R.id.decision_submitting);
            card.addView(submitting);
            return;
        }
        if (!decisionAnswerableState(state)) {
            // withdrawn / expired / stale / answered: no actions, explicit copy only.
            return;
        }
        if (!canAnswerDecisions()) {
            TextView disabled = UiText.small(this, getString(R.string.decision_answer_m2_1));
            disabled.setId(R.id.decision_actions_disabled);
            disabled.setTextColor(getColor(R.color.faint));
            card.addView(disabled);
            return;
        }
        String kind = decision.kind == null ? "decision" : decision.kind;
        if ("question".equals(kind) && decision.options.isEmpty()) {
            // PO v2.1 guard: empty options[] must degrade to source-problem, never answerable.
            TextView problem = UiText.small(this, getString(R.string.question_empty_options));
            problem.setId(R.id.decision_source_problem);
            problem.setTextColor(getColor(R.color.amber));
            card.addView(problem);
            DshLog.decisionsSource("question-options-missing");
            return;
        }
        if ("approval".equals(kind)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button allow = actionButton(getString(R.string.approval_allow_once), R.color.accent, true);
            allow.setId(R.id.approval_allow);
            allow.setOnClickListener(v -> showApprovalConfirm(decision));
            row.addView(allow);
            Button reject = actionButton(getString(R.string.approval_reject_label), R.color.surface_2, false);
            reject.setId(R.id.approval_reject);
            reject.setOnClickListener(v -> startRejectUndo(decision));
            LinearLayout.LayoutParams rejectParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            rejectParams.leftMargin = dp(8);
            row.addView(reject, rejectParams);
            card.addView(row);
            if ("reject-pending".equals(state)) {
                LinearLayout undoRow = new LinearLayout(this);
                undoRow.setOrientation(LinearLayout.HORIZONTAL);
                undoRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView note = UiText.small(this, getString(R.string.approval_rejecting));
                note.setTextColor(getColor(R.color.amber));
                undoRow.addView(note, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button undo = actionButton(getString(R.string.approval_undo_label),
                        R.color.surface_2, false);
                undo.setId(R.id.approval_undo);
                undo.setOnClickListener(v -> undoReject(decision));
                undoRow.addView(undo);
                card.addView(undoRow);
            }
        } else if ("question".equals(kind)) {
            final java.util.Set<String> selected = questionSelections.computeIfAbsent(
                    decision.id, k -> new java.util.LinkedHashSet<>());
            if (decision.recommendation != null && selected.isEmpty()) {
                for (String option : decision.options) {
                    if (option.equalsIgnoreCase(decision.recommendation)) selected.add(option);
                }
            }
            LinearLayout options = new LinearLayout(this);
            options.setOrientation(LinearLayout.VERTICAL);
            options.setId(R.id.question_options);
            for (String option : decision.options) {
                TextView chip = UiText.small(this, (selected.contains(option) ? "✓ " : "○ ") + option);
                chip.setContentDescription("question_option:" + option);
                chip.setTextColor(getColor(selected.contains(option) ? R.color.accent : R.color.text_soft));
                chip.setOnClickListener(v -> {
                    if (decision.multiple) {
                        if (!selected.remove(option)) selected.add(option);
                    } else {
                        selected.clear();
                        selected.add(option);
                    }
                    renderCurrentDecisionSurface();
                });
                options.addView(chip);
            }
            card.addView(options);
            EditText custom = new EditText(this);
            custom.setId(R.id.question_custom);
            custom.setHint(R.string.question_custom_hint);
            custom.setTextSize(12);
            custom.setTextColor(getColor(R.color.text));
            custom.setHintTextColor(getColor(R.color.faint));
            custom.setBackgroundResource(R.drawable.bg_input);
            card.addView(custom);
            TextView delivery = UiText.small(this, "next-turn".equals(decision.delivery)
                    ? getString(R.string.question_delivery_next_turn)
                    : getString(R.string.question_delivery_inline));
            delivery.setId(R.id.question_delivery_note);
            delivery.setTextColor(getColor(R.color.faint));
            card.addView(delivery);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button submit = actionButton(getString(R.string.question_submit_label), R.color.accent, true);
            submit.setId(R.id.question_submit);
            submit.setOnClickListener(v -> answerDecision(decision,
                    questionPayload(decision, selected, custom.getText().toString().trim()), "submit"));
            row.addView(submit);
            Button skip = actionButton(getString(R.string.question_skip_label), R.color.surface_2, false);
            skip.setId(R.id.question_skip);
            skip.setOnClickListener(v -> dismissDecision(decision));
            LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            skipParams.leftMargin = dp(8);
            row.addView(skip, skipParams);
            card.addView(row);
        } else {
            if (decision.recommendation != null) {
                Button recommended = actionButton(getString(R.string.decision_go_recommended),
                        R.color.accent, true);
                recommended.setId(R.id.decision_primary_action);
                recommended.setOnClickListener(v -> answerDecision(decision,
                        recommendedPayload(), "recommendation"));
                card.addView(recommended);
            } else {
                EditText answerText = new EditText(this);
                answerText.setId(R.id.decision_answer_text);
                answerText.setHint(R.string.decision_answer_hint);
                answerText.setTextSize(13);
                answerText.setTextColor(getColor(R.color.text));
                answerText.setHintTextColor(getColor(R.color.faint));
                answerText.setBackgroundResource(R.drawable.bg_input);
                card.addView(answerText);
                Button submit = actionButton(getString(R.string.question_submit_label),
                        R.color.accent, true);
                submit.setId(R.id.decision_primary_action);
                submit.setOnClickListener(v -> {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("text", answerText.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    answerDecision(decision, payload, "text");
                });
                card.addView(submit);
            }
        }
        if (!"approval".equals(kind)) {
            TextView dismiss = UiText.small(this, getString(R.string.decision_dismiss_label));
            dismiss.setId(R.id.decision_dismiss);
            dismiss.setTextColor(getColor(R.color.faint));
            dismiss.setOnClickListener(v -> dismissDecision(decision));
            card.addView(dismiss);
        }
    }

    private Button actionButton(String label, int colorRes, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(getColor(primary ? R.color.on_accent : R.color.text_soft));
        button.setBackgroundResource(primary ? R.drawable.bg_button : R.drawable.bg_button_ghost);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        return button;
    }

    private JSONObject approvalPayload(String decisionValue) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("decision", decisionValue);
        } catch (Exception ignored) {
        }
        return payload;
    }

    private JSONObject recommendedPayload() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("recommended", true);
        } catch (Exception ignored) {
        }
        return payload;
    }

    private JSONObject questionPayload(Models.Decision decision, java.util.Set<String> selected,
                                       String custom) {
        JSONObject payload = new JSONObject();
        try {
            JSONArray answers = new JSONArray();
            JSONObject answer = new JSONObject();
            String questionKey = decision.questionId != null ? decision.questionId : decision.id;
            answer.put("questionId", questionKey);
            answer.put("id", questionKey);
            JSONArray picked = new JSONArray();
            for (String label : selected) picked.put(label);
            answer.put("selected", picked);
            if (custom != null && !custom.isEmpty()) answer.put("custom", custom);
            answers.put(answer);
            payload.put("answers", answers);
        } catch (Exception ignored) {
        }
        return payload;
    }

    /** PO v2.1: Reject is single-tap with a 3 s undo window; approval (Allow) is not undoable. */
    private void startRejectUndo(Models.Decision decision) {
        if (decision == null || decision.id == null) return;
        if (rejectTimers.containsKey(decision.id)) return;
        decisionLocalState.put(decision.id, "reject-pending");
        renderCurrentDecisionSurface();
        Runnable timer = () -> {
            rejectTimers.remove(decision.id);
            if ("reject-pending".equals(decisionLocalState.get(decision.id))) {
                decisionLocalState.remove(decision.id);
                answerDecision(decision, approvalPayload("reject"), "reject");
            }
        };
        rejectTimers.put(decision.id, timer);
        streamHandler.postDelayed(timer, 3000L);
    }

    private void undoReject(Models.Decision decision) {
        if (decision == null || decision.id == null) return;
        Runnable timer = rejectTimers.remove(decision.id);
        if (timer != null) streamHandler.removeCallbacks(timer);
        decisionLocalState.remove(decision.id);
        renderCurrentDecisionSurface();
    }

    private void showApprovalConfirm(Models.Decision decision) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setId(R.id.approval_confirm_sheet);
        body.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView tool = UiText.small(this, getString(R.string.approval_tool_label) + ": "
                + (decision.toolName == null ? "unknown" : decision.toolName));
        tool.setId(R.id.approval_tool);
        body.addView(tool);
        TextView args = UiText.small(this, getString(R.string.approval_args_label) + ": "
                + (decision.detail == null || decision.detail.isEmpty() ? "unknown" : decision.detail));
        args.setId(R.id.approval_args);
        body.addView(args);
        boolean unknownWorkspace = decision.workspacePath == null;
        TextView workspace = UiText.small(this, getString(R.string.approval_workspace_label) + ": "
                + (unknownWorkspace ? getString(R.string.decision_workspace_unknown)
                : decision.workspacePath));
        workspace.setId(R.id.approval_workspace);
        body.addView(workspace);
        TextView session = UiText.small(this, getString(R.string.approval_session_label) + ": "
                + (decision.sessionTitle == null ? getString(R.string.session_untitled)
                : decision.sessionTitle));
        body.addView(session);
        TextView warning = UiText.small(this, getString(R.string.approval_sheet_body));
        body.addView(warning);
        final android.widget.CheckBox ack = new android.widget.CheckBox(this);
        ack.setId(R.id.approval_workspace_ack);
        ack.setText(getString(R.string.approval_workspace_ack_label));
        ack.setTextSize(12);
        ack.setTextColor(getColor(R.color.amber));
        ack.setVisibility(unknownWorkspace ? View.VISIBLE : View.GONE);
        body.addView(ack);
        final Button confirm = actionButton(getString(R.string.approval_allow_once),
                R.color.accent, true);
        confirm.setId(R.id.approval_allow_confirm);
        confirm.setEnabled(!unknownWorkspace);
        Button cancel = actionButton(getString(R.string.approval_cancel_label),
                R.color.surface_2, false);
        cancel.setId(R.id.approval_cancel);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.addView(cancel);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        confirmParams.leftMargin = dp(8);
        buttons.addView(confirm, confirmParams);
        body.addView(buttons);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.approval_sheet_title)
                .setView(body)
                .create();
        ack.setOnCheckedChangeListener((buttonView, checked) -> confirm.setEnabled(checked));
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            answerDecision(decision, approvalPayload("allow"), "allow");
        });
        dialog.show();
    }

    private void dismissDecision(Models.Decision decision) {
        decisionDismissed.add(decision.id);
        DshLog.decisionDismiss(decision.id);
        renderCurrentDecisionSurface();
    }

    private void renderCurrentDecisionSurface() {
        if (showingDecisions) {
            renderDecisions();
        } else if (chatOpen && transcriptAdapter != null) {
            transcriptAdapter.notifyDataSetChanged();
        }
    }

    private void answerDecision(Models.Decision decision, JSONObject payload, String action) {
        if (decision == null || decision.id == null) return;
        if (decisionSubmitting.contains(decision.id)) return;
        decisionSubmitting.add(decision.id);
        decisionLocalState.put(decision.id, "submitting");
        DshLog.decisionAnswerStart(decision.id, action);
        renderCurrentDecisionSurface();
        io.execute(() -> {
            try {
                JSONObject response = api.answerDecision(decision.id, payload);
                boolean delivered = response.optBoolean("delivered", false);
                String where = response.optString("where", "");
                runOnUiThread(() -> {
                    decisionSubmitting.remove(decision.id);
                    decisionLocalState.put(decision.id, "answered");
                    decisionRetryPayload.remove(decision.id);
                    DshLog.decisionAnswerOk(decision.id, delivered);
                    Toast.makeText(MainActivity.this, delivered && !where.isEmpty()
                            ? getString(R.string.decision_delivered_fmt, where) : "Answer sent",
                            Toast.LENGTH_SHORT).show();
                    if (showingDecisions) loadDecisionsFeed();
                    renderCurrentDecisionSurface();
                });
            } catch (Exception error) {
                String message = String.valueOf(error.getMessage());
                runOnUiThread(() -> {
                    decisionSubmitting.remove(decision.id);
                    if (message.contains("409") || message.toLowerCase().contains("stale")) {
                        decisionLocalState.put(decision.id, "stale");
                        DshLog.decisionAnswerFail(decision.id, "decision-stale");
                    } else {
                        decisionLocalState.put(decision.id, "failed");
                        decisionRetryPayload.put(decision.id, payload);
                        DshLog.decisionAnswerFail(decision.id, message);
                    }
                    renderCurrentDecisionSurface();
                });
            }
        });
    }

    private final class DecisionAdapter extends BaseAdapter {
        private final List<Models.Decision> decisions;

        DecisionAdapter(List<Models.Decision> decisions) {
            this.decisions = decisions;
        }

        @Override
        public int getCount() {
            return decisions.size();
        }

        @Override
        public Object getItem(int position) {
            return decisions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return decisionCardView(decisions.get(position), false);
        }
    }

    /** Tiny text helpers for the read-only interaction cards. */
    private static final class UiText {
        static TextView bold(android.content.Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextColor(context.getResources().getColor(R.color.text, context.getTheme()));
            view.setTextSize(14);
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            return view;
        }

        static TextView small(android.content.Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextColor(context.getResources().getColor(R.color.muted, context.getTheme()));
            view.setTextSize(12);
            view.setPadding(0, 2, 0, 2);
            return view;
        }
    }

    private void showYou() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setId(R.id.you_screen);
        scroll.setBackgroundColor(getColor(R.color.bg));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        root.addView(label(getString(R.string.host)));
        TextView hostUrl = value(baseUrl);
        hostUrl.setId(R.id.host_url);
        hostUrl.setContentDescription("Host URL " + baseUrl);
        root.addView(hostUrl);
        root.addView(label(getString(R.string.connection)));
        root.addView(value(getString(R.string.connected) + (cookie.isEmpty() ? "" : " · cookie")));
        root.addView(label(getString(R.string.about)));
        root.addView(value(getString(R.string.app_version)));
        root.addView(value(getString(R.string.webview_value)));

        Button unpair = new Button(this);
        unpair.setId(R.id.disconnect_button);
        unpair.setText(R.string.unpair);
        unpair.setTextColor(getColor(R.color.red));
        unpair.setBackgroundResource(R.drawable.bg_button_ghost);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(24);
        unpair.setLayoutParams(params);
        unpair.setOnClickListener(v -> confirmUnpair());
        root.addView(unpair);
        content.removeAllViews();
        content.addView(scroll);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.muted));
        view.setTextSize(11);
        view.setLetterSpacing(0.06f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(0, dp(16), 0, dp(4));
        return view;
    }

    private TextView value(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.text_soft));
        view.setTextSize(14);
        view.setTextIsSelectable(true);
        return view;
    }

    // ---------------------------------------------------------------- helpers

    private void goBack() {
        if (chatOpen) {
            showTab(TAB_CHATS);
            return;
        }
        if (tab != TAB_CHATS) {
            showTab(TAB_CHATS);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private void search() {
        EditText input = new EditText(this);
        input.setHint(R.string.search_hint);
        input.setText(query);
        new AlertDialog.Builder(this)
                .setTitle(R.string.search)
                .setView(input)
                .setNegativeButton(R.string.back, null)
                .setPositiveButton(R.string.search, (dialog, which) -> {
                    query = input.getText().toString().trim().toLowerCase();
                    showTab(TAB_CHATS);
                })
                .show();
    }

    private String agentDisplayName(Models.Agent agent) {
        if ("human".equals(agent.role)) return getString(R.string.you_operator);
        if ("lead".equals(agent.role)) return "Lead";
        String candidate = null;
        if (data != null && agent.sessionId != null && !agent.sessionId.isEmpty()) {
            for (Models.Session session : data.sessions) {
                if (session.id.equals(agent.sessionId)
                        && session.agentName != null && !session.agentName.isEmpty()) {
                    candidate = session.agentName;
                    break;
                }
            }
        }
        if (candidate == null || candidate.isEmpty()) candidate = agent.name;
        String name = candidate == null ? "" : candidate.trim();
        // Contract §5 falls back to the session title when agentTeams is absent; recover a
        // human label from the "<Role>: <task>" prefix, else show the role.
        if (name.contains(":")) {
            String prefix = name.substring(0, name.indexOf(':')).trim();
            if (isRoleWord(prefix)) return prefix;
        }
        if (name.length() <= 24 && !name.contains(" — ") && !name.contains(" · ")) return name;
        String role = agent.role == null ? "" : agent.role;
        if ("teammate".equals(role)) return "Teammate";
        if ("subagent".equals(role)) return "Subagent";
        return role.isEmpty() ? "Agent" : Character.toUpperCase(role.charAt(0)) + role.substring(1);
    }

    private static boolean isRoleWord(String value) {
        String lower = value.toLowerCase();
        return lower.contains("lead") || lower.contains("architect") || lower.contains("backend")
                || lower.contains("back-end") || lower.contains("frontend") || lower.contains("front-end")
                || lower.contains("product") || lower.contains("project") || lower.contains("qa")
                || lower.contains("quality") || lower.contains("design") || lower.contains("manager");
    }

    /** Human first, Lead, teammates, then subagents — one row per agent. */
    private List<Models.Agent> roster() {
        List<Models.Agent> ordered = new ArrayList<>();
        List<Models.Agent> teammates = new ArrayList<>();
        List<Models.Agent> others = new ArrayList<>();
        Models.Agent human = null;
        Models.Agent lead = null;
        if (data != null) {
            for (Models.Agent agent : data.agents) {
                if ("human".equals(agent.role)) human = agent;
                else if ("lead".equals(agent.role)) lead = agent;
                else if ("teammate".equals(agent.role)) teammates.add(agent);
                else others.add(agent);
            }
        }
        if (human == null) {
            human = new Models.Agent();
            human.id = "you";
            human.sessionId = null;
            human.name = getString(R.string.you_operator);
            human.role = "human";
            human.status = "inactive";
            human.description = getString(R.string.you_operator);
            human.avatarSeed = "you";
        }
        ordered.add(human);
        if (lead != null) ordered.add(lead);
        ordered.addAll(teammates);
        ordered.addAll(others);
        return ordered;
    }

    private void showMembers() {
        if (data == null || data.agents.isEmpty()) {
            Toast.makeText(this, R.string.agents_empty_title, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Models.Agent> agents = roster();
        List<String> names = new ArrayList<>();
        for (Models.Agent agent : agents) {
            names.add(agentDisplayName(agent) + " · " + agent.role + " · " + agent.status);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.members)
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Models.Agent agent = agents.get(which);
                    if ("human".equals(agent.role)) {
                        Toast.makeText(this, getString(R.string.you_operator) + " · "
                                + agent.status, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (agent.sessionId != null && !agent.sessionId.isEmpty()) {
                        for (Models.Session session : data.sessions) {
                            if (session.id.equals(agent.sessionId)) {
                                openSession(session);
                                return;
                            }
                        }
                    }
                    Toast.makeText(this, agentDisplayName(agent) + " · " + agent.status,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.back, null)
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.refresh);
        menu.getMenu().add(0, 2, 1, R.string.copy_host_url);
        menu.getMenu().add(0, 3, 2, R.string.unpair);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                load();
                return true;
            }
            if (item.getItemId() == 2) {
                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("host", baseUrl));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (item.getItemId() == 3) {
                confirmUnpair();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.unpair_confirm)
                .setNegativeButton(R.string.back, null)
                .setPositiveButton(R.string.unpair, (dialog, which) -> {
                    prefs.clear();
                    startActivity(new Intent(this, ConnectActivity.class));
                    finish();
                })
                .show();
    }

    private static final int ERROR_OFFLINE = 0;
    private static final int ERROR_401 = 1;
    private static final int ERROR_403 = 2;

    private void showError(int kind, String detail) {
        if (kind == ERROR_OFFLINE && data != null) {
            android.view.View cached = findViewById(R.id.offline_banner);
            if (cached != null) cached.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.error_offline_title, Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        int titleRes;
        int bodyRes;
        int actionRes;
        int actionKind;
        if (kind == ERROR_401) {
            titleRes = R.string.error_401_title;
            bodyRes = R.string.error_401_body;
            actionRes = R.string.re_pair;
            actionKind = ERROR_401;
        } else if (kind == ERROR_403) {
            titleRes = R.string.error_403_title;
            bodyRes = R.string.error_403_body;
            actionRes = R.string.retry;
            actionKind = ERROR_403;
        } else {
            titleRes = R.string.error_offline_title;
            bodyRes = R.string.error_offline_body;
            actionRes = R.string.retry;
            actionKind = ERROR_OFFLINE;
        }
        android.view.View banner = findViewById(R.id.offline_banner);
        if (banner != null) banner.setVisibility(kind == ERROR_OFFLINE ? View.VISIBLE : View.GONE);
        LinearLayout root = new LinearLayout(this);
        root.setId(R.id.error_state);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView body = new TextView(this);
        String shown = getString(bodyRes);
        if (detail != null && !detail.isEmpty()) shown = shown + "\n\n" + detail;
        body.setText(shown);
        body.setTextColor(getColor(R.color.muted));
        body.setTextSize(13);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(8), 0, dp(16));
        root.addView(body);
        Button retry = new Button(this);
        retry.setId(R.id.retry_button);
        retry.setText(actionRes);
        retry.setBackgroundResource(R.drawable.bg_button);
        retry.setTextColor(getColor(R.color.on_accent));
        final int finalKind = actionKind;
        retry.setOnClickListener(v -> {
            if (finalKind == ERROR_401) {
                prefs.clear();
                startActivity(new Intent(this, ConnectActivity.class));
                finish();
            } else {
                load();
            }
        });
        root.addView(retry, new LinearLayout.LayoutParams(dp(160), dp(46)));
        content.removeAllViews();
        content.addView(root);
    }

    private TextView emptyView(String title, String body) {
        TextView view = new TextView(this);
        view.setId(R.id.empty_state);
        view.setText(title + "\n" + body);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getColor(R.color.muted));
        view.setTextSize(13);
        view.setPadding(dp(24), dp(24), dp(24), dp(24));
        return view;
    }

    private View find(View root, int primary, int fallback) {
        View view = root.findViewById(primary);
        if (view == null && fallback != 0) view = root.findViewById(fallback);
        return view;
    }

    private View find(int primary, int fallback) {
        View view = findViewById(primary);
        if (view == null && fallback != 0) view = findViewById(fallback);
        return view;
    }

    private TextView tv(View root, int primary, int fallback) {
        TextView view = root.findViewById(primary);
        if (view == null && fallback != 0) view = root.findViewById(fallback);
        return view;
    }

    private static String elapsed(Models.Job job) {
        long end = job.finishedAt > 0 ? job.finishedAt : System.currentTimeMillis();
        long delta = Math.max(0, end - job.startedAt);
        if (delta < 60_000L) return (delta / 1000L) + "s";
        if (delta < 3_600_000L) return (delta / 60_000L) + "m";
        return (delta / 3_600_000L) + "h";
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ---------------------------------------------------------------- adapters

    private final class ChannelAdapter extends BaseAdapter {
        private final List<Row> rows;

        ChannelAdapter(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.type == ROW_TEST_TOGGLE) {
                // ADR-0010 §2: the TEST block header. NOT a channel_row, NOT a
                // channel_group_header, NOT a channel_system_row — its own id +
                // channel_test_toggle:<collapsed|expanded>:<N> content-desc. Text "TEST · N".
                LinearLayout view;
                if (convertView instanceof LinearLayout
                        && convertView.getId() == R.id.channel_test_toggle) {
                    view = (LinearLayout) convertView;
                } else {
                    view = new LinearLayout(MainActivity.this);
                    view.setId(R.id.channel_test_toggle);
                    view.setOrientation(LinearLayout.HORIZONTAL);
                    view.setGravity(Gravity.CENTER_VERTICAL);
                    view.setMinimumHeight(dp(48));
                    view.setPadding(dp(12), 0, dp(12), 0);
                }
                view.removeAllViews();
                int count = 0;
                try {
                    count = Integer.parseInt(row.subtitle);
                } catch (NumberFormatException ignored) {
                }
                boolean expanded = "expanded".equals(row.special);
                view.setContentDescription("channel_test_toggle:" + row.special + ":" + count);
                TextView glyph = new TextView(MainActivity.this);
                glyph.setText(expanded ? "▾" : "▸");
                glyph.setTextSize(12);
                glyph.setTextColor(getColor(R.color.muted));
                glyph.setPadding(0, 0, dp(8), 0);
                view.addView(glyph, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView titleView = new TextView(MainActivity.this);
                titleView.setText(row.title);
                titleView.setTextSize(12);
                titleView.setTextColor(getColor(R.color.text_soft));
                titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                view.addView(titleView, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView stateView = new TextView(MainActivity.this);
                stateView.setText(getString(expanded
                        ? R.string.test_toggle_shown : R.string.test_toggle_hidden));
                stateView.setTextSize(12);
                stateView.setTextColor(getColor(R.color.faint));
                view.addView(stateView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                return view;
            }
            if (row.type == ROW_SECTION) {
                View view = convertView != null ? convertView : getLayoutInflater()
                        .inflate(R.layout.item_section, parent, false);
                TextView header = tv(view, R.id.channel_group_header, R.id.section_title);
                if (header != null) header.setText(row.title);
                TextView caption = view.findViewById(R.id.channel_group_caption);
                if (caption != null) {
                    if (TextUtils.isEmpty(row.subtitle)) {
                        caption.setVisibility(View.GONE);
                    } else {
                        caption.setText(row.subtitle);
                        caption.setVisibility(View.VISIBLE);
                    }
                }
                return view;
            }
            if (row.type == ROW_MESSAGE) {
                TextView view = emptyView(row.title, row.subtitle);
                view.setId(R.id.channel_empty);
                return view;
            }
            View view = convertView != null ? convertView : getLayoutInflater()
                    .inflate(R.layout.item_channel, parent, false);
            TextView icon = view.findViewById(R.id.channel_icon);
            TextView title = tv(view, R.id.channel_row_title, R.id.channel_title);
            TextView subtitle = tv(view, R.id.channel_row_snippet, R.id.channel_subtitle);
            TextView state = tv(view, R.id.channel_row_state, R.id.channel_count);
            TextView time = view.findViewById(R.id.channel_row_time);
            View dot = find(view, R.id.channel_row_unread, R.id.unread_dot);
            if (title == null) return view;
            title.setText(row.title);
            if (row.session == null) {
                view.setId(R.id.channel_system_row);
                view.setTag(row.special);
                String systemState = row.subtitle;
                view.setContentDescription("channel_system_row:" + row.special
                        + (systemState == null ? "" : ", " + systemState));
                icon.setText("#");
                if (subtitle != null) subtitle.setVisibility(View.GONE);
                if (state != null) {
                    if (systemState == null) {
                        state.setVisibility(View.GONE);
                    } else {
                        state.setVisibility(View.VISIBLE);
                        state.setText(systemState);
                        state.setTextColor(getColor(R.color.amber));
                    }
                }
                if (time != null) time.setVisibility(View.GONE);
                if (dot != null) dot.setVisibility(View.GONE);
                return view;
            }
            Models.Session session = row.session;
            boolean teamRow = session.teamId != null;
            view.setId(R.id.channel_row);
            view.setTag(session.id);
            view.setPaddingRelative(dp(12) + row.indent * dp(14), view.getPaddingTop(),
                    view.getPaddingEnd(), view.getPaddingBottom());
            View indentMarker = view.findViewById(R.id.channel_row_team_indent);
            if (teamRow) {
                if (indentMarker == null) {
                    indentMarker = new View(MainActivity.this);
                    indentMarker.setId(R.id.channel_row_team_indent);
                    ((ViewGroup) view).addView(indentMarker, 0, new LinearLayout.LayoutParams(
                            dp(2) + row.indent * dp(6), ViewGroup.LayoutParams.MATCH_PARENT));
                } else {
                    indentMarker.setLayoutParams(new LinearLayout.LayoutParams(
                            dp(2) + row.indent * dp(6), ViewGroup.LayoutParams.MATCH_PARENT));
                }
                indentMarker.setBackgroundColor(getColor(R.color.accent));
            } else if (indentMarker != null) {
                ((ViewGroup) view).removeView(indentMarker);
            }
            icon.setText(session.running ? "●" : (session.waiting ? "◐" : "○"));
            icon.setTextColor(getColor(session.running ? R.color.green
                    : (session.waiting ? R.color.amber : R.color.faint)));
            if (subtitle != null) {
                subtitle.setVisibility(row.subtitle == null ? View.GONE : View.VISIBLE);
                if (row.subtitle != null) subtitle.setText(row.subtitle);
            }
            String stateText = session.running ? "Running" : (session.waiting ? "Waiting" : "Idle");
            if (state != null) { state.setVisibility(View.VISIBLE); state.setText(stateText); }
            if (time != null) { time.setVisibility(View.VISIBLE); time.setText(relativeTime(session.updatedAt)); }
            boolean unread = session.updatedAt > prefs.lastRead(session.id);
            if (dot != null) dot.setVisibility(unread ? View.VISIBLE : View.GONE);
            DshLog.unreadCompute(session.id, unread);
            view.setContentDescription("channel_row:" + session.title + ", " + stateText
                    + (unread ? ", unread" : "")
                    + (teamRow ? ", team_depth=" + row.indent : ""));
            return view;
        }
    }

    private static String relativeTime(long time) {
        if (time <= 0) return "";
        long delta = System.currentTimeMillis() - time;
        if (delta < 60_000L) return "now";
        if (delta < 3_600_000L) return (delta / 60_000L) + "m";
        if (delta < 86_400_000L) return (delta / 3_600_000L) + "h";
        if (delta < 172_800_000L) return "Yesterday";
        return (delta / 86_400_000L) + "d";
    }

    private final class MessageAdapter extends BaseAdapter {
        private final String sessionId;
        private final List<Models.Message> messages;
        private String streamText;
        private boolean streamTextDebug;

        MessageAdapter(String sessionId, List<Models.Message> messages) {
            this.sessionId = sessionId;
            this.messages = messages;
        }

        String streamText() {
            return streamText;
        }

        void setStreamText(String value) {
            setStreamText(value, false);
        }

        void setStreamText(String value, boolean debug) {
            if (value == null && streamText == null) return;
            streamText = value;
            streamTextDebug = value != null && debug;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return messages.size() + (streamText != null ? 1 : 0);
        }

        @Override
        public Object getItem(int position) {
            return messages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView : getLayoutInflater()
                    .inflate(R.layout.item_message, parent, false);
            if (position >= messages.size()) {
                TextView role = tv(view, R.id.chat_row_role, R.id.message_role);
                TextView text = tv(view, R.id.chat_row_text, R.id.message_text);
                TextView time = tv(view, R.id.chat_row_time, R.id.message_meta);
                if (role == null || text == null) return view;
                view.setId(R.id.chat_row_streaming);
                view.setTag(sessionId + ":stream");
                // Architect/QA contract: exactly chat_row:streaming (synthetic identity comes from
                // the source=debug-hook marker, not the content-desc).
                view.setContentDescription("chat_row:streaming");
                role.setText("assistant");
                role.setTextColor(getColor(R.color.accent));
                text.setText(streamText + "▍");
                text.setMaxLines(Integer.MAX_VALUE);
                if (time != null) time.setVisibility(View.GONE);
                return view;
            }
            Models.Message message = messages.get(position);
            TextView role = tv(view, R.id.chat_row_role, R.id.message_role);
            TextView text = tv(view, R.id.chat_row_text, R.id.message_text);
            TextView time = tv(view, R.id.chat_row_time, R.id.message_meta);
            if (role == null || text == null) return view;
            if (message.decision != null && "decision".equals(message.kind)) {
                return decisionCardView(message.decision, true);
            }
            if ("sending".equals(message.status) || "failed".equals(message.status)) {
                boolean failed = "failed".equals(message.status);
                view.setId(R.id.chat_row);
                view.setTag(sessionId + ":pending:" + message.clientRequestId);
                view.setContentDescription("chat_row:pending:" + message.status
                        + (failed ? ", tap to retry" : ""));
                role.setText(failed ? "you · failed · tap to retry" : "you · sending");
                role.setTextColor(getColor(failed ? R.color.red : R.color.muted));
                text.setText(message.text);
                text.setMaxLines(Integer.MAX_VALUE);
                if (time != null) time.setVisibility(View.GONE);
                view.setOnClickListener(failed ? v -> retrySend(message) : null);
                return view;
            }
            String roleText = "assistant";
            int color = R.color.accent;
            if ("user".equals(message.role)) {
                roleText = "user";
                color = R.color.accent_2;
            } else if ("tool".equals(message.role)) {
                roleText = "tool";
                color = R.color.amber;
            } else if ("system".equals(message.role)) {
                roleText = "system";
                color = R.color.muted;
            }
            boolean tool = "tool".equals(message.role);
            view.setId(tool ? R.id.chat_tool_row : R.id.chat_row);
            view.setTag(sessionId + ":" + message.seq);
            view.setContentDescription((tool ? "chat_tool_row:" : "chat_row:")
                    + message.role + ":" + message.seq);
            role.setText(roleText);
            role.setTextColor(getColor(color));
            text.setText(message.text);
            text.setTextSize(15);
            text.setTextColor(getColor(R.color.text));
            text.setMaxLines(Integer.MAX_VALUE);
            text.setEllipsize(null);
            view.setOnClickListener(null);
            if (tool) {
                text.setMaxLines(2);
                view.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle(message.toolName == null ? "tool" : message.toolName)
                        .setMessage(message.text)
                        .setPositiveButton("Close", null)
                        .show());
            } else if ("system".equals(message.role)) {
                // R6: collapse runtime-context / notice walls to a 2-line chip; tap expands.
                boolean collapsed = message.text != null
                        && (message.text.length() > 160 || message.text.split("\n").length > 3);
                if (collapsed) {
                    role.setText("notice");
                    text.setMaxLines(2);
                    text.setEllipsize(TextUtils.TruncateAt.END);
                    text.setTextSize(12);
                    text.setTextColor(getColor(R.color.muted));
                    view.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle("notice")
                            .setMessage(message.text)
                            .setPositiveButton("Close", null)
                            .show());
                } else {
                    text.setTextSize(13);
                    text.setTextColor(getColor(R.color.muted));
                }
            }
            if (message.seq > 0) {
                time.setVisibility(View.VISIBLE);
                time.setText("#" + message.seq + " · " + message.kind);
            } else {
                time.setVisibility(View.GONE);
            }
            return view;
        }
    }

    private final class CardAdapter extends BaseAdapter {
        private final List<Card> cards;

        CardAdapter(List<Card> cards) {
            this.cards = cards;
        }

        @Override
        public int getCount() {
            return cards.size();
        }

        @Override
        public Object getItem(int position) {
            return cards.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 5;
        }

        @Override
        public int getItemViewType(int position) {
            return cards.get(position).kind;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Card card = cards.get(position);
            if (card.kind == CARD_SECTION) {
                View section = convertView != null ? convertView : getLayoutInflater()
                        .inflate(R.layout.item_section, parent, false);
                TextView header = section.findViewById(R.id.channel_group_header);
                if (header != null) header.setText(card.title);
                TextView caption = section.findViewById(R.id.channel_group_caption);
                if (caption != null) caption.setVisibility(View.GONE);
                if (card.title != null && card.title.startsWith("RESTART REQUIRED")) {
                    section.setId(R.id.plugin_restart_banner);
                    section.setContentDescription("plugin_restart_banner");
                } else {
                    // Reset so a recycled banner view never keeps the banner resource-id.
                    section.setId(View.NO_ID);
                    section.setContentDescription("plugin_section:" + card.title);
                }
                return section;
            }
            View view = convertView != null ? convertView : getLayoutInflater()
                    .inflate(R.layout.item_card, parent, false);
            TextView title = view.findViewById(R.id.card_title);
            TextView status = view.findViewById(R.id.card_status);
            TextView subtitle = view.findViewById(R.id.card_subtitle);
            TextView body = view.findViewById(R.id.card_body);
            Switch toggle = (Switch) find(view, R.id.plugin_enabled_switch, R.id.card_switch);
            TextView reason = view.findViewById(R.id.plugin_readonly_reason);
            if (title == null || status == null || subtitle == null || body == null) return view;
            title.setText(card.title);
            status.setText(card.status);
            subtitle.setText(card.subtitle);
            subtitle.setTextColor(getColor(R.color.muted));
            subtitle.setOnClickListener(null);
            body.setText(card.body);
            body.setVisibility(card.body == null || card.body.isEmpty() ? View.GONE : View.VISIBLE);
            if (toggle != null) toggle.setVisibility(View.GONE);
            if (reason != null) reason.setVisibility(View.GONE);
            view.setTag(card.tag);
            switch (card.kind) {
                case CARD_AGENT:
                    view.setId(R.id.agent_row);
                    title.setId(R.id.agent_name);
                    status.setId(R.id.agent_status);
                    subtitle.setId(R.id.agent_role);
                    view.setContentDescription("agent_row:" + card.title + ":" + card.status);
                    break;
                case CARD_PLUGIN:
                    view.setId(R.id.plugin_row);
                    title.setId(R.id.plugin_title);
                    status.setId(R.id.plugin_phase);
                    view.setContentDescription("plugin_row:" + card.title + ":" + card.status
                            + (card.pluginReadOnlyReason == null ? "" : ":read-only"));
                    if (toggle != null) {
                        if (AppConfig.M1_READ_ONLY) {
                            toggle.setVisibility(card.pluginEntryId != null ? View.VISIBLE : View.GONE);
                            toggle.setEnabled(false);
                            toggle.setChecked(card.pluginEnabled);
                            toggle.setOnCheckedChangeListener(null);
                        } else if (card.pluginEntryId != null && card.pluginTogglable) {
                            toggle.setVisibility(View.VISIBLE);
                            toggle.setOnCheckedChangeListener(null);
                            toggle.setChecked(card.pluginEnabled);
                            toggle.setOnCheckedChangeListener((button, checked) -> togglePlugin(card, checked));
                        } else {
                            toggle.setOnCheckedChangeListener(null);
                        }
                    }
                    if (reason != null && card.pluginReadOnlyReason != null
                            && !card.pluginReadOnlyReason.isEmpty()) {
                        reason.setVisibility(View.VISIBLE);
                        reason.setText(getString(R.string.read_only_reason, card.pluginReadOnlyReason));
                    }
                    break;
                case CARD_JOB:
                    view.setId(R.id.job_row);
                    status.setId(R.id.job_status);
                    body.setId(R.id.job_output_view);
                    view.setContentDescription("job_row:" + card.title + ":" + card.status);
                    if (card.ownerSessionId != null && !card.ownerSessionId.isEmpty()) {
                        subtitle.setTextColor(getColor(R.color.accent));
                        subtitle.setOnClickListener(v -> openSessionById(card.ownerSessionId));
                    }
                    break;
                default:
                    view.setId(R.id.decision_row);
                    title.setId(R.id.decision_question);
                    subtitle.setId(R.id.decision_session);
                    status.setId(R.id.decision_state);
                    view.setContentDescription("decision_row:" + card.title + ":" + card.status
                            + (card.decisionUrgent ? ":urgent" : ""));
                    if (card.decisionUrgent) {
                        title.setTextColor(getColor(R.color.amber));
                        status.setId(R.id.decision_urgent);
                    }
                    if (card.decisionRecommendation != null) {
                        body.setVisibility(View.VISIBLE);
                        body.setId(R.id.decision_recommendation);
                        body.setText("Recommendation: " + card.decisionRecommendation);
                    }
                    break;
            }
            return view;
        }
    }
}
