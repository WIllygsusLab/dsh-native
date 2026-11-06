package dev.dsh.harness;

import java.util.Locale;

/**
 * ADR-0010 — the Test channel is a client-side classification (view filter).
 *
 * Pure static predicate over the projected bootstrap Session rows: reads exactly
 * {@code title} and {@code cwd}, sends nothing, mutates nothing. Evaluated in order;
 * first match assigns the reason tag; membership is "any match". The prefix list is
 * closed — new patterns become explicit numbered rules, never heuristics.
 *
 * Input normalization (ADR-0010 §1):
 *  - title: trim first, then case-fold (Locale.ROOT); null title counts as "".
 *  - cwd: case-sensitive, literal (paths are not prose). Null cwd counts as "".
 *  - guard (before all rules): {@code origin == "subagent"} → never classified. Plain
 *    subagents never reach the classifier (plainSubagent() rows are filtered earlier in
 *    channelRows()); the guard additionally protects named teammates (origin=subagent +
 *    teamRole) from vanishing into Test. Amended 2026-11-02: the guard is origin, not
 *    teamId — 232 of 244 rule-matching rows carry teamId on this host (ADR-0010 §4.4).
 *
 * Deliberately NOT classified (ADR-0010 §4): "Untitled session" in a real workspace,
 * imperative-looking titles without a rule prefix, anything matching on preview/prompt
 * (preview is never an input — it changes every turn), archived sessions.
 */
public final class TestClassifier {
    private TestClassifier() {
    }

    /** True iff any rule R1–R8 matches (the §1 guard applies first). */
    public static boolean isTest(Models.Session session) {
        return reasonOf(session) != null;
    }

    /**
     * Reason tag of the FIRST matching rule, or null when not classified. The tag is
     * evidence/diagnostics only — the order matters for the label, never for membership.
     */
    public static String reasonOf(Models.Session session) {
        if (session == null) return null;
        if ("subagent".equals(session.origin)) return null; // §1 guard (amended)
        String t = session.title == null ? "" : session.title.trim().toLowerCase(Locale.ROOT);
        String c = session.cwd == null ? "" : session.cwd;
        if (t.startsWith("m2gate")) return "title:m2gate"; // R1 — the gate/probe seeds
        // R2 — after R1 so gate rows tag m2gate; the only "contains" in the set, gated
        // behind the m2 prefix ("M2 phone test: reply with").
        if (t.startsWith("m2") && t.contains("test")) return "title:m2+test";
        if (t.startsWith("probe")) return "title:probe"; // R3 — probe fleet
        if (t.startsWith("test")) return "title:test"; // R4 — bare test/testing…
        // R5 — "qa", "qa smoke", "qa_123"; NOT "qax" or "qaengineer".
        if (t.equals("qa") || t.startsWith("qa ") || t.startsWith("qa_")) return "title:qa";
        // R6 — typed seed prompts echoed as titles (the self-answering seed convention).
        if (t.startsWith("reply with exactly")) return "title:seed";
        // R7 — host scratch/system workspaces (same literal isSystemWorkspace() already uses).
        if (c.contains("/.hermes/")) return "cwd:hermes";
        // R8 — tmp scratch; literal so "dsh-ideas-link" (/tmp/dsh-ideas-link) does NOT match.
        if (c.equals("/tmp") || c.startsWith("/tmp/")) return "cwd:tmp";
        return null;
    }
}
