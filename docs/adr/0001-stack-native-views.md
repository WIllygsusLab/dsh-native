# ADR-0001 — Stack: Gradle-free native Android Views in Java

- Status: **accepted** (Lead resolution, M0)
- Date: 2026-10-31 (proposed and accepted)
- Deciders: architect (recommendation), lead (resolution), frontend (implementation)
- Supersedes: nothing · Fallback recorded below (unchanged by the resolution)

## Resolution

Accepted by **Lead** on **2026-10-31**, on the recommendation of **architect** (`architect`).

> Gradle-free native framework Views, Java, zero dependencies is the binding stack for
> M1/M2. Record the resolution in the ADR (status: accepted by Lead, date, your name) and
> keep the M3 re-platform fallback as written.
> — `lead`, M0-03 resolution, message `team-message-54e03c73`

Binding consequences: no androidx, no Gradle, and no third-party dependency may be added to
`app-native/` in M1/M2. Any need for one is escalated to `lead`, not absorbed by a developer.
The M3 re-platform fallback below stands as written and may only be invoked through a new
superseding ADR.

### Package amendment (accepted hard constraint, 2026-10-31)

The shipped package is **`dev.dsh.harness`**, not `dev.dsh.native`: `native` is a Java reserved
keyword, so `package dev.dsh.native;` cannot compile. This is a language constraint, not a
deviation from this ADR — the app is still "Harness Native" (label) and still framework Views,
Java, zero dependencies, Gradle-free. All docs use `dev.dsh.harness` from M1 onward.

## Context

`dsh-android/` already ships a working Android app built **without Gradle**:

- `build.sh` runs `aapt2 → javac → d8 → zipalign → apksigner` (README "Build").
- Toolchain is installed and proven: JDK 17 at `~/android-toolchain/jdk-17.0.21+9`,
  SDK at `~/android-toolchain/sdk` (`platforms;android-34`, `build-tools;34.0.0`).
- The existing app is Java + framework Views, with a full drawable set
  (`app/res/drawable/*.xml`: cards, sheets, pills, buttons, icons) and a dark theme.
- Build output is a signed APK in `dist/`, installable on the emulator and the Galaxy the phone.

The new product (`app-native/`, `dev.dsh.harness`) needs: server rail, channel list, chat
transcript with streaming append, member drawer, plugin deck, jobs, decisions, bottom tabs.
It must feel fast and native, and it must be buildable on this machine without network
dependency resolution.

The two candidate stacks:

1. **Native Views, Java, Gradle-free** — reuse the existing toolchain, zero dependencies.
2. **Compose, Kotlin, Gradle** — modern declarative UI, androidx dependency graph.

## Decision

**Use native Android framework Views in Java, built with the existing Gradle-free
`aapt2/javac/d8/apksigner` pipeline, and add no third-party dependencies.**

Concretely:

| Concern | Choice |
| --- | --- |
| Language | Java 17, source/target 17 |
| UI | `Activity`, framework `View`/`ViewGroup`, `ListView` + `BaseAdapter` + ViewHolder, custom `View`/`Drawable` |
| Navigation | one `MainActivity`, tab content swap, custom per-tab `ArrayDeque` back stack |
| HTTP | `HttpURLConnection` (with `CookieManager` / own `CookieJar`) |
| SSE | hand-written `BufferedReader` line reader over the response stream |
| JSON | `org.json` (shipped in the Android platform) |
| Persistence | framework `SQLiteOpenHelper` + private `SharedPreferences` |
| Crypto | `android.security.keystore` AES-GCM for the cookie |
| Package / SDK | `dev.dsh.harness`, `minSdk 26`, `targetSdk 34`, `compileSdk 34` |
| Build | `./build-native.sh` at the repo root (copy of `build.sh` pointed at `app-native/`); an identical mirror lives at `app-native/build-native.sh` — keep **one** (M1 uses the root script) |

## Why (build cost and risk)

**Cost.** M1 shell + read-only API: ~4,500 LOC Java (5 screens, 3 overlays, adapter layer,
HTTP/SSE/repository). M2 live chat + control: ~+3,000 LOC. Incremental build ~30 s, clean
build < 2 min. No dependency download, so it works offline and on a fresh checkout.

**Risk.** Compose requires: Gradle + AGP + Kotlin + ~40 androidx artifacts (2–3 GB of
downloads), a `gradle` daemon, and network access for first resolution. None of that exists
in the current toolchain, so adopting it adds a new failure domain to every build — and the
machine has no guarantee of network at build time. Gradle-free has **zero** new toolchain
risk: the exact pipeline already produces a signed APK today.

**The price we accept.** No `RecyclerView`, no Material components, no Compose animation
APIs, no ViewPager. We hand-roll:

- list recycling (`convertView` + ViewHolder tag) — `ListView` is sufficient for ≤ 500 rows
  and handles variable-height chat rows;
- press/ripple feedback (`StateListDrawable` + `RippleDrawable` on API 21+);
- swipe-to-reveal (long-press menu first; `GestureDetector` for swipe in M2);
- tab bar and rail as custom `LinearLayout` + drawables.

Discord-mobile *feel* is mostly spacing, avatars, pills, and instant navigation — all
achievable with framework Views. The Compose advantage is motion polish, which is not on the
M1/M2 critical path.

## Consequences

- **frontend** must not add androidx or any Maven dependency. If a task seems to need one,
  escalate to `architect`/`lead` instead of adding Gradle.
- Reuse `app/res/drawable/` and `app/res/values/` where possible; fork them into
  `app-native/res/` so the old app stays intact.
- Message list must be a **reversed `ListView`** with prepend pagination and a single-row
  notify on streaming append (see ADR-0004/0005).
- Performance budget must be measured on the the phone at 200 messages before M2 is declared
  done; if the list cannot hold 55 fps, that is a review finding, not a silent rewrite.
- QA matrix includes API 26 (minSdk) for `RippleDrawable`/`Keystore` behaviour.

## Fallback

If at the **M3 gate** motion/accessibility requirements cannot be met with framework Views,
the fallback is Compose/Gradle as a *re-platform* of `app-native/` — not an incremental
change. Conditions for invoking it: (a) the M1/M2 feature set is complete and accepted,
(b) the Gradle toolchain has been installed and proven on this machine, (c) the Lead records
the decision as a new ADR superseding this one. Do not start it silently mid-milestone.
