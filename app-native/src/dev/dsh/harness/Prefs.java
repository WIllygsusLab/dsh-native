package dev.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;

/** Host + pairing state for one DSH host. */
public final class Prefs {
    private static final String NAME = "dsh_native";
    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        return sp.getString("baseUrl", "");
    }

    public String token() {
        return sp.getString("token", "");
    }

    public String cookie() {
        return sp.getString("cookie", "");
    }

    public boolean paired() {
        return !baseUrl().isEmpty();
    }

    public void save(String baseUrl, String token, String cookie) {
        sp.edit().putString("baseUrl", baseUrl).putString("token", token).putString("cookie", cookie).apply();
    }

    public long lastRead(String sessionId) {
        return sp.getLong("read:" + sessionId, 0L);
    }

    public void setLastRead(String sessionId, long time) {
        sp.edit().putLong("read:" + sessionId, time).apply();
    }

    /** cwd of the last chat opened in the app — the new-session sheet's second default. */
    public String lastCwd() {
        return sp.getString("lastCwd", "");
    }

    public void setLastCwd(String cwd) {
        if (cwd == null || cwd.isEmpty()) return;
        sp.edit().putString("lastCwd", cwd).apply();
    }

    /**
     * ADR-0010 §3: whether the Test group is expanded. Persists expansion state only — the
     * classification itself is always-on and non-configurable. Default false (collapsed).
     */
    public boolean showTestSessions() {
        return sp.getBoolean("showTestSessions", false);
    }

    public void setShowTestSessions(boolean show) {
        sp.edit().putBoolean("showTestSessions", show).apply();
    }

    /**
     * Rail collapse (team/design/rail-collapse-spec.md): whether the workspace rail is hidden.
     * Default true — the rail is on demand via the top-bar hamburger. Persists user choice only.
     */
    public boolean railHidden() {
        return sp.getBoolean("railHidden", true);
    }

    public void setRailHidden(boolean hidden) {
        sp.edit().putBoolean("railHidden", hidden).apply();
    }

    public void clear() {
        sp.edit().clear().apply();
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
