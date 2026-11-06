package dev.dsh.harness;

/** Build-time flags. No PII or secrets are ever logged. */
public final class AppConfig {
    public static final String VERSION = "0.1.0";
    public static final String BUILD = "M2-01";
    public static final boolean DEBUG = true;
    /** M1 gate: controls are rendered read-only; M2 control code stays behind this flag. */
    public static final boolean M1_READ_ONLY = false;
    /** Logcat tag QA greps: adb logcat -s DshNative */
    public static final String LOG_TAG = "DshNative";

    private AppConfig() {}
}
