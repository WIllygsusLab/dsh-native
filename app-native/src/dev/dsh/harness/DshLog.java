package dev.dsh.harness;

import android.util.Log;

/**
 * Debug marker channel for QA (m1-frontend-testability.md §C).
 * Format: "EVENT <name> key=value …" on tag DshNative. No cookies, tokens or bodies.
 */
public final class DshLog {
    private DshLog() {}

    public static void marker(String line) {
        emit(line);
    }

    private static void emit(String line) {
        if (AppConfig.DEBUG) {
            Log.i(AppConfig.LOG_TAG, line);
        }
    }

    public static void appStart() {
        emit("EVENT app.start");
    }

    public static void lifecycle(String state) {
        emit("EVENT lifecycle state=" + state);
    }

    public static void pairSuccess(String origin) {
        emit("EVENT pair.success host=" + origin);
    }

    public static void bootstrapRequest() {
        emit("EVENT bootstrap.request");
    }

    public static void bootstrapDone(long ms, int sessions, int agents, int plugins) {
        emit("EVENT bootstrap.done ms=" + ms + " sessions=" + sessions + " agents=" + agents
                + " plugins=" + plugins);
    }

    public static void unreadCompute(String sessionId, boolean dot) {
        emit("EVENT unread.compute sessionId=" + sessionId + " dot=" + dot);
    }

    public static void channelGroup(String name, int count) {
        emit("EVENT channel.group name=" + name + " count=" + count);
    }

    public static void decisionOpen(String decisionId, String surface) {
        emit("EVENT decision.open decisionId=" + decisionId + " surface=" + surface);
    }

    public static void decisionAnswerStart(String decisionId, String action) {
        emit("EVENT decision.answer.start decisionId=" + decisionId + " action=" + action);
    }

    public static void decisionAnswerOk(String decisionId, boolean delivered) {
        emit("EVENT decision.answer.ok decisionId=" + decisionId + " delivered=" + delivered);
    }

    public static void decisionAnswerFail(String decisionId, String reason) {
        emit("EVENT decision.answer.fail decisionId=" + decisionId + " reason=" + reason.replace(' ', '_'));
    }

    public static void decisionAnswerRetry(String decisionId) {
        emit("EVENT decision.answer.retry decisionId=" + decisionId);
    }

    public static void decisionDismiss(String decisionId) {
        emit("EVENT decision.dismiss decisionId=" + decisionId);
    }

    public static void decisionsSource(String problem) {
        emit("EVENT decisions.source problem=" + problem.replace(' ', '_'));
    }

    public static void decisionsLoad(String source, int waiting, int urgent) {
        emit("EVENT decisions.load source=" + source + " waiting=" + waiting + " urgent=" + urgent);
    }

    public static void authRejected(int status, String path) {
        emit("EVENT auth.rejected status=" + status + " path=" + path);
    }

    public static void pluginsList(int rows, int extensions, int core) {
        emit("EVENT plugins.list rows=" + rows + " extensions=" + extensions + " core=" + core);
    }

    public static void pluginToggle(String entryId, boolean enabled, String result) {
        emit("EVENT plugins.toggle entry=" + entryId + " enabled=" + enabled + " result=" + result);
    }

    public static void sessionPage(String sessionId, String before, int count, boolean hasMore) {
        emit("EVENT session.page sessionId=" + sessionId + " before=" + before + " count=" + count
                + " hasMore=" + hasMore);
    }

    public static void transcriptRender(String sessionId, long firstSeq, long lastSeq, int rows) {
        emit("EVENT transcript.render sessionId=" + sessionId + " firstSeq=" + firstSeq
                + " lastSeq=" + lastSeq + " rows=" + rows);
    }

    public static void messageAppend(String sessionId, long seq) {
        emit("EVENT message.append sessionId=" + sessionId + " seq=" + seq);
    }

    public static void messageDedupe(String sessionId, long seq, String reason) {
        emit("EVENT message.dedupe sessionId=" + sessionId + " seq=" + seq + " reason=" + reason);
    }

    public static void appendVisible(String sessionId, long seq, long ms) {
        emit("EVENT append.visible sessionId=" + sessionId + " seq=" + seq + " ms=" + ms);
    }

    public static void sendPending(String requestId, String sessionId) {
        emit("EVENT send.pending requestId=" + requestId + " sessionId=" + sessionId);
    }

    public static void sendStart(String requestId, String sessionId, int chars) {
        emit("EVENT send.start requestId=" + requestId + " sessionId=" + sessionId
                + " chars=" + chars);
    }

    public static void sendOk(String requestId, long seq) {
        emit("EVENT send.ok requestId=" + requestId + " seq=" + seq);
    }

    public static void sendFail(String requestId, String reason) {
        emit("EVENT send.fail requestId=" + requestId + " reason=" + reason.replace(' ', '_'));
    }

    public static void sendEcho(String requestId, String match, long seq) {
        emit("EVENT send.echo requestId=" + requestId + " match=" + match + " seq=" + seq);
    }

    public static void sendRetry(String requestId) {
        emit("EVENT send.retry requestId=" + requestId);
    }

    public static void streamConnect(int since) {
        emit("EVENT stream.connect since=" + since);
    }

    public static void streamHello(String streamId, int replayFrom, int heartbeatMs) {
        emit("EVENT stream.hello streamId=" + streamId + " replayFrom=" + replayFrom
                + " heartbeatMs=" + heartbeatMs);
    }

    public static void streamFrame(int id, String event) {
        emit("EVENT stream.frame id=" + id + " event=" + event);
    }

    public static void streamResync(String reason) {
        emit("EVENT stream.resync reason=" + reason);
    }

    public static void chatOpen(String sessionId, String title) {
        emit("EVENT chat.open sessionId=" + sessionId + " title=" + title.replace(' ', '_'));
    }

    public static void sessionCreate(String cwd) {
        boolean hasCwd = cwd != null && !cwd.isEmpty();
        emit("EVENT session.create cwd=" + (hasCwd ? cwd.replace(' ', '_') : "default"));
    }

    /** Which preset the sheet is about to send; `default` means agentPreset was omitted. */
    public static void sessionCreatePreset(String presetId) {
        emit("EVENT session.create.preset preset=" + (presetId == null || presetId.isEmpty()
                ? "default" : presetId));
    }

    public static void sessionCreateOk(String sessionId) {
        emit("EVENT session.create.ok sessionId=" + sessionId);
    }

    public static void sessionCreateFail(String reason) {
        emit("EVENT session.create.fail reason=" + reason.replace(' ', '_'));
    }

    public static void sheetOpen(boolean presets) {
        emit("EVENT sheet.open kind=new_session presets=" + presets);
    }

    /** Which workspace the sheet resolved for cwd, and why: rail|last-open|main|first|none. */
    public static void sheetWorkspaceDefault(String path, String source) {
        emit("EVENT sheet.workspace.default path="
                + (path == null || path.isEmpty() ? "none" : path.replace(' ', '_'))
                + " source=" + source);
    }

    public static void workspacePick(String path) {
        emit("EVENT workspace.pick path="
                + (path == null || path.isEmpty() ? "none" : path.replace(' ', '_')));
    }

    public static void presetPick(String presetId) {
        emit("EVENT preset.pick preset=" + (presetId == null || presetId.isEmpty()
                ? "default" : presetId));
    }

    public static void presetsLoad(int count) {
        emit("EVENT presets.load count=" + count);
    }

    public static void presetsLoadFail(String reason) {
        emit("EVENT presets.load.fail reason=" + reason.replace(' ', '_'));
    }

    public static void streamDrop(String sessionId, String reason) {
        emit("EVENT stream.drop sessionId=" + sessionId + " reason=" + reason);
    }

    public static void streamDisconnect(String reason) {
        emit("EVENT stream.disconnect reason=" + reason);
    }

    public static void streamReconnect(int attempt, long delayMs) {
        emit("EVENT stream.reconnect attempt=" + attempt + " delayMs=" + delayMs);
    }
}
