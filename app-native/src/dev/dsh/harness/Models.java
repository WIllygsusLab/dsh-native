package dev.dsh.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Wire models for /api/mobile/v1, kept deliberately small (api-contract §3.0–§8). */
public final class Models {
    private Models() {}

    public static final class Workspace {
        public String id = "";
        public String title = "";
        public String path = "";

        public static Workspace from(JSONObject o) {
            Workspace w = new Workspace();
            w.id = o.optString("workspaceId", o.optString("id", ""));
            w.title = o.optString("title", "");
            w.path = o.optString("path", "");
            return w;
        }
    }

    public static final class Session {
        public String id = "";
        public String title = "Untitled session";
        public String preview;
        public String cwd = "";
        public String workspaceId = "";
        public boolean running;
        public boolean waiting;
        public boolean blank;
        public int pendingDecisions;
        public String origin;
        public String parentSessionId;
        public String teamId;
        public String teamRole;
        public String agentId;
        public String agentName;
        public String lastError;
        public int delegationDepth = -1;
        public long updatedAt;
        public String model;

        public static Session from(JSONObject o) {
            Session s = new Session();
            s.id = o.optString("sessionId", "");
            s.title = o.optString("title", "Untitled session");
            s.preview = o.isNull("lastMessagePreview") ? null : o.optString("lastMessagePreview", null);
            s.cwd = o.optString("cwd", "");
            s.workspaceId = o.optString("workspaceId", "");
            s.running = o.optBoolean("running", false);
            s.waiting = o.optBoolean("waiting", false);
            s.blank = o.optBoolean("blank", false);
            s.pendingDecisions = o.optInt("pendingDecisionCount", 0);
            s.origin = o.isNull("origin") ? null : o.optString("origin", null);
            s.parentSessionId = o.isNull("parentSessionId") ? null : o.optString("parentSessionId", null);
            s.teamId = o.isNull("teamId") ? null : o.optString("teamId", null);
            s.teamRole = o.isNull("teamRole") ? null : o.optString("teamRole", null);
            s.agentId = o.isNull("agentId") ? null : o.optString("agentId", null);
            s.agentName = o.isNull("agentName") ? null : o.optString("agentName", null);
            s.lastError = o.isNull("lastError") ? null : o.optString("lastError", null);
            s.delegationDepth = o.optInt("delegationDepth", -1);
            s.updatedAt = o.optLong("updatedAt", 0);
            JSONObject model = o.optJSONObject("model");
            s.model = model == null ? null : model.optString("model", null);
            return s;
        }

        /** Plain subagents are not listed (contract §3.4); team members are. */
        public boolean plainSubagent() {
            return "subagent".equals(origin) && teamId == null;
        }

        public boolean team() {
            return teamId != null && !waiting;
        }
    }

    public static final class Message {
        public long seq;
        public String role = "assistant";
        public String kind = "message";
        public String text = "";
        /** Echo of the send requestId on the durable user message (contract §3.2). */
        public String clientRequestId;
        /** complete | streaming | interrupted | failed | sending (client-side "sending"/"failed"). */
        public String status = "complete";
        public String peerName;
        public Models.Decision decision;
        public String toolName;
        public String toolStatus;
        public boolean toolError;

        public static Message from(JSONObject o) {
            Message m = new Message();
            m.seq = o.optLong("seq", 0);
            m.role = o.optString("role", "assistant");
            m.kind = o.optString("kind", "message");
            m.text = o.optString("text", "");
            m.clientRequestId = o.isNull("clientRequestId")
                    ? null : o.optString("clientRequestId", null);
            m.status = o.optString("status", "complete");
            m.peerName = o.isNull("peerName") ? null : o.optString("peerName", null);
            JSONObject decisionJson = o.optJSONObject("decision");
            if (decisionJson != null) m.decision = Models.Decision.from(decisionJson);
            JSONObject tool = o.optJSONObject("tool");
            if (tool != null) {
                m.toolName = tool.optString("name", "");
                m.toolError = tool.optBoolean("isError", false);
                m.toolStatus = tool.optBoolean("isError", false) ? "error" : "ok";
                if (m.text.isEmpty()) {
                    String summary = tool.optString("argumentsSummary", "");
                    m.text = m.toolName + (summary.isEmpty() ? "" : " · " + summary);
                }
            }
            return m;
        }
    }

    public static final class Agent {
        public String id = "";
        public String sessionId = "";
        public String name = "agent";
        public String role = "agent";
        public String status = "inactive";
        public String description = "";
        public String model;
        public String avatarSeed = "";

        public static Agent from(JSONObject o) {
            Agent a = new Agent();
            a.id = o.optString("agentId", o.optString("sessionId", ""));
            a.sessionId = o.optString("sessionId", "");
            a.name = o.optString("name", "agent");
            a.role = o.optString("role", "agent");
            a.status = o.optString("status", "inactive");
            a.description = o.isNull("description") ? "" : o.optString("description", "");
            JSONObject model = o.optJSONObject("model");
            a.model = model == null ? null : model.optString("model", null);
            a.avatarSeed = o.optString("avatarSeed", a.name);
            return a;
        }
    }

    public static final class PluginCard {
        public String entryId = "";
        public String title = "";
        public String module = "";
        public String description = "";
        public String version = "";
        public String group = "";
        public String phase = "";
        public boolean enabled;
        public boolean readOnly;
        public boolean togglable;
        public String readOnlyReason;
        public boolean restartRequired;

        public static PluginCard from(JSONObject o) {
            PluginCard p = new PluginCard();
            p.entryId = o.optString("entryId", "");
            p.title = o.isNull("title") ? "" : o.optString("title", "");
            p.module = o.optString("moduleName", "");
            p.description = o.isNull("description") ? "" : o.optString("description", "");
            p.version = o.optString("version", "");
            p.group = o.optString("group", "");
            p.enabled = o.optBoolean("enabled", false);
            p.phase = o.isNull("fiberPhase") ? "" : o.optString("fiberPhase", "");
            p.readOnly = o.optBoolean("readOnly", false);
            p.togglable = o.optBoolean("togglable", false);
            p.readOnlyReason = o.isNull("readOnlyReason") ? null : o.optString("readOnlyReason", null);
            p.restartRequired = o.optBoolean("restartRequired", false);
            if (p.title.isEmpty()) p.title = humanize(p.module.isEmpty() ? p.entryId : p.module);
            return p;
        }

        /** "dsh-plugin-agent-terminals" -> "Agent Terminals"; fixes null/blank host titles. */
        public static String humanize(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) return "Plugin";
            value = value.replace("dsh-plugin-", "").replace("dsh-", "");
            value = value.replace('-', ' ').replace('_', ' ').trim();
            StringBuilder out = new StringBuilder();
            for (String word : value.split("\\s+")) {
                if (word.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.length() == 0 ? raw : out.toString();
        }
    }

    public static final class Job {
        public String id = "";
        public String label = "";
        public String kind = "";
        public String status = "";
        public String sessionId = "";
        public String ownerSessionId = "";
        public String sessionTitle = "";
        public String progress = "";
        public String detail = "";
        public long startedAt;
        public long finishedAt;

        public static Job from(JSONObject o) {
            Job j = new Job();
            j.id = o.optString("jobId", "");
            j.label = o.optString("label", j.id);
            j.kind = o.optString("kind", "");
            j.status = o.optString("status", "");
            j.ownerSessionId = o.optString("ownerSessionId", "");
            j.sessionId = o.optString("sessionId", j.ownerSessionId);
            j.sessionTitle = o.optString("sessionTitle", o.optString("ownerSessionTitle", ""));
            j.progress = o.isNull("progress") ? "" : o.optString("progress", "");
            j.detail = o.isNull("detail") ? "" : o.optString("detail", "");
            j.startedAt = o.optLong("startedAt", 0);
            j.finishedAt = o.optLong("finishedAt", 0);
            return j;
        }
    }

    public static final class Decision {
        public String id = "";
        public String source = "";
        public String kind = "question";
        public String answerMode = "";
        public String sessionId = "";
        public String ownerSessionId = "";
        public String sessionTitle = "";
        public String title = "Decision";
        public String prompt = "";
        public String detail = "";
        public String recommendation;
        public final List<String> options = new ArrayList<>();
        public final List<String> optionIds = new ArrayList<>();
        public boolean multiple;
        public String delivery;
        public String intent;
        public String toolName;
        public String workspacePath;
        public String questionId;
        public String callId;
        public boolean delivered;
        public String state = "pending";
        public boolean urgent;
        public boolean running;
        public long waitedMs;
        public long createdAt;

        public static Decision from(JSONObject o) {
            Decision d = new Decision();
            d.id = o.optString("decisionId", o.optString("id", ""));
            d.source = o.optString("source", "");
            d.kind = o.optString("kind", "question");
            d.answerMode = o.optString("answerMode", "");
            d.sessionId = o.optString("sessionId", "");
            d.sessionTitle = o.optString("sessionTitle", "");
            d.title = o.optString("title", "Decision");
            d.prompt = o.optString("prompt", o.optString("question", ""));
            d.detail = o.isNull("detail") ? "" : o.optString("detail", "");
            d.recommendation = o.isNull("recommendation") ? null : o.optString("recommendation", null);
            d.state = o.optString("state", "pending");
            d.urgent = o.optBoolean("urgent", false);
            d.running = o.optBoolean("running", false);
            d.waitedMs = o.optLong("waitedMs", 0);
            d.createdAt = o.optLong("createdAt", 0);
            // PO v2.2: per-item canonical field is multiSelect; flattened is multiple.
            d.multiple = o.optBoolean("multiSelect", o.optBoolean("multiple", false));
            d.delivery = o.isNull("delivery") ? null : o.optString("delivery", null);
            d.intent = o.isNull("intent") ? null : o.optString("intent", null);
            d.toolName = o.isNull("toolName") ? null : o.optString("toolName", null);
            d.workspacePath = o.isNull("workspacePath") ? null : o.optString("workspacePath", null);
            d.questionId = o.isNull("questionId") ? null : o.optString("questionId", null);
            d.callId = o.isNull("callId") ? null : o.optString("callId", null);
            d.delivered = o.optBoolean("delivered", false);
            JSONArray options = o.optJSONArray("options");
            if (options != null) {
                for (int i = 0; i < options.length(); i++) {
                    JSONObject option = options.optJSONObject(i);
                    if (option != null) {
                        d.options.add(option.optString("label", option.optString("id", "")));
                        d.optionIds.add(option.optString("id", ""));
                    }
                }
            }
            return d;
        }
    }

    public static final class Bootstrap {
        public String hostName = "dsh";
        public String hostProfile = "";
        public String hostVersion = "";
        public long generatedAt;
        public final List<Workspace> workspaces = new ArrayList<>();
        public final List<Session> sessions = new ArrayList<>();
        public final List<Agent> agents = new ArrayList<>();
        public final List<PluginCard> plugins = new ArrayList<>();
        public final List<Job> jobs = new ArrayList<>();
        public final List<Decision> decisions = new ArrayList<>();
        /** capabilities.decisionAnswer — false while the answer path is disabled (read-only cards). */
        public boolean decisionAnswer;
        /** capabilities.agentPresets — false/absent hides the new-session model picker (task-5). */
        public boolean agentPresets;
        public String decisionsSourceProblem;
        public boolean decisionsAnswerable;
        public int decisionsWaiting;
        public int decisionsUrgent;

        public static Bootstrap from(JSONObject o) {
            Bootstrap b = new Bootstrap();
            b.generatedAt = o.optLong("generatedAt", 0);
            JSONObject capabilities = o.optJSONObject("capabilities");
            if (capabilities != null) {
                b.decisionAnswer = capabilities.optBoolean("decisionAnswer", false);
                b.agentPresets = capabilities.optBoolean("agentPresets", false);
            }
            JSONObject decisionsFeed = o.optJSONObject("decisionsFeed");
            if (decisionsFeed != null) {
                b.decisionsAnswerable = decisionsFeed.optBoolean("answerable", false);
                b.decisionsSourceProblem = decisionsFeed.isNull("sourceProblem")
                        ? null : decisionsFeed.optString("sourceProblem", null);
                JSONObject badge = decisionsFeed.optJSONObject("badge");
                if (badge != null) {
                    b.decisionsWaiting = badge.optInt("waiting", 0);
                    b.decisionsUrgent = badge.optInt("urgent", 0);
                }
            }
            JSONObject host = o.optJSONObject("host");
            if (host != null) {
                b.hostName = host.optString("name", "dsh");
                b.hostProfile = host.optString("profile", "");
                b.hostVersion = host.optString("version", "");
            }
            JSONArray sessions = o.optJSONArray("sessions");
            if (sessions != null) {
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject row = sessions.optJSONObject(i);
                    if (row != null) b.sessions.add(Session.from(row));
                }
            }
            JSONArray agents = o.optJSONArray("agents");
            if (agents != null) {
                for (int i = 0; i < agents.length(); i++) {
                    JSONObject row = agents.optJSONObject(i);
                    if (row != null) b.agents.add(Agent.from(row));
                }
            }
            JSONObject plugins = o.optJSONObject("plugins");
            JSONArray entries = plugins == null ? null : plugins.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject row = entries.optJSONObject(i);
                    if (row != null) b.plugins.add(PluginCard.from(row));
                }
            }
            JSONArray jobs = o.optJSONArray("jobs");
            if (jobs != null) {
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject row = jobs.optJSONObject(i);
                    if (row != null) b.jobs.add(Job.from(row));
                }
            }
            JSONArray decisions = o.optJSONArray("decisions");
            if (decisions != null) {
                for (int i = 0; i < decisions.length(); i++) {
                    JSONObject row = decisions.optJSONObject(i);
                    if (row != null) b.decisions.add(Decision.from(row));
                }
            }
            JSONArray workspaces = o.optJSONArray("workspaces");
            if (workspaces != null) {
                for (int i = 0; i < workspaces.length(); i++) {
                    JSONObject row = workspaces.optJSONObject(i);
                    if (row != null) b.workspaces.add(Workspace.from(row));
                }
            }
            return b;
        }
    }

    /** GET /presets row (v1ag): agentPreset choices for the new-session sheet (task-5). */
    public static final class Preset {
        public String id = "";
        public String name;
        public String description;
        public boolean isDefault;
        public boolean broken;
        public String reason;
        /** Optional additive presets.model (contract §3 P2): provider + modelId, either may be absent. */
        public String modelProvider;
        public String modelId;

        public String label() {
            return name == null || name.isEmpty() ? id : name;
        }

        /** "provider · modelId" / "modelId" for the row's secondary line, or null when absent. */
        public String modelLine() {
            if (modelId == null || modelId.isEmpty()) return null;
            return modelProvider == null || modelProvider.isEmpty()
                    ? modelId : modelProvider + " · " + modelId;
        }

        public static List<Preset> listFrom(JSONObject body) {
            List<Preset> out = new ArrayList<>();
            if (body == null) return out;
            JSONArray array = body.optJSONArray("presets");
            if (array == null) array = body.optJSONArray("items");
            if (array == null) return out;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                Preset p = new Preset();
                p.id = row.optString("id", row.optString("preset", ""));
                if (p.id.isEmpty()) continue;
                p.name = row.isNull("name") ? null : row.optString("name", null);
                p.description = row.isNull("description") ? null : row.optString("description", null);
                p.isDefault = row.optBoolean("isDefault", false);
                // Contract §3 says `broken` is a boolean, but the live v1ag registry projects
                // the failure diagnostic itself (`broken: "<reason>"`). Accept both shapes:
                // a non-empty string means broken and doubles as the human-readable reason.
                Object brokenRaw = row.opt("broken");
                String brokenDiagnostic = null;
                if (brokenRaw instanceof Boolean) {
                    p.broken = (Boolean) brokenRaw;
                } else if (brokenRaw instanceof String) {
                    brokenDiagnostic = ((String) brokenRaw).trim();
                    p.broken = !brokenDiagnostic.isEmpty();
                }
                if (!row.optBoolean("available", true)) p.broken = true;
                String reason = row.isNull("reason") ? null : row.optString("reason", null);
                if (reason == null || reason.isEmpty()) {
                    reason = row.isNull("error") ? null : row.optString("error", null);
                }
                if ((reason == null || reason.isEmpty()) && brokenDiagnostic != null) {
                    reason = brokenDiagnostic;
                }
                p.reason = reason;
                // Optional additive `model` (P2): tolerant parse — String id, {provider,model}
                // object, absent, or garbage all leave the row intact (never drop on bad shape).
                Object modelRaw = row.opt("model");
                if (modelRaw instanceof String) {
                    String value = ((String) modelRaw).trim();
                    if (!value.isEmpty()) p.modelId = value;
                } else if (modelRaw instanceof JSONObject) {
                    JSONObject modelJson = (JSONObject) modelRaw;
                    Object providerRaw = modelJson.opt("provider");
                    Object modelValueRaw = modelJson.opt("model");
                    if (!(modelValueRaw instanceof String)) modelValueRaw = modelJson.opt("modelId");
                    if (providerRaw instanceof String) {
                        String provider = ((String) providerRaw).trim();
                        if (!provider.isEmpty()) p.modelProvider = provider;
                    }
                    if (modelValueRaw instanceof String) {
                        String modelValue = ((String) modelValueRaw).trim();
                        if (!modelValue.isEmpty()) p.modelId = modelValue;
                    }
                }
                out.add(p);
            }
            return out;
        }
    }
}
