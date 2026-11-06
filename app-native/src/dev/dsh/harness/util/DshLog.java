package dev.dsh.harness.util;

import android.util.Log;

import dev.dsh.harness.AppConfig;

/**
 * The single debug marker channel for QA (strategy.md R3 / cases M1-05, M1-20, M1-32).
 * Tag: DshNative. Never log cookies, tokens, message bodies or URLs with a token param.
 */
public final class DshLog {
    private DshLog() {}

    public static void marker(String event) {
        if (AppConfig.DEBUG) {
            Log.i(AppConfig.LOG_TAG, event);
        }
    }

    public static void lifecycle(String event) {
        marker("lifecycle=" + event);
    }

    public static void screen(String kind, String route) {
        marker("screen=" + kind + " route=" + route);
    }

    public static void nav(String op, String route) {
        marker("nav=" + op + " route=" + route);
    }

    public static void tab(String tab) {
        marker("tab=" + tab);
    }

    public static void connection(String state, String detail) {
        marker("connection=" + state + (detail == null ? "" : " detail=" + detail));
    }

    public static void pair(String result, String host) {
        marker("pair=" + result + " host=" + host);
    }

    public static void pairSuccess(String host) {
        pair("success", host);
    }

    public static void event(String type, long seq) {
        marker("event-received type=" + type + " seq=" + seq);
    }

    public static void rowRendered(long seq, long appendLatencyMs) {
        marker("row-rendered seq=" + seq + " append-latency ms=" + appendLatencyMs);
    }

    public static void error(String where, String message) {
        marker("error where=" + where + " message=" + message);
    }
}
