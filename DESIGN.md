Version 3 · revised round 3

# Design thesis

- **User:** CloudStream viewers on Android phones (one-handed portrait touch, OLED screens in dim rooms) and Android TV / Google TV boxes (10-foot D-pad remote navigation). They open StreamPlay Settings to switch subtitle language between English and Urdu, tune provider concurrency, curate active sources, or add subtitle/addon credentials, then get back to watching within seconds.
- **Job:** Configure playback subtitles, source concurrency, active providers, and addon integrations with immediate effect and zero guesswork.
- **Hero moment:** One-tap switching between English and Urdu subtitles in the top control card: immediate visual state transition (150 ms spring-eased surface shift), haptic tick, live source-readiness summary, and instant application to the next stream or download without restarting the app.
- **Thesis:** *A quiet, high-contrast broadcast control deck that shows live state at a glance and acts in one tap.*
- **Is:** Instrument-grade, high-contrast, direct.
- **Is not:** Marketing banner (no self-congratulatory "SOTA" badges), nested card maze (no boxes inside boxes with glowing borders), settings scavenger hunt (no blind rows hiding current values behind modals).
- **Anchors:**
  1. *Braun T1000 / Dieter Rams control panels:* hierarchy through typographic weight, grouping, and spacing rather than decorative borders or colored status lights.
  2. *Sony CineAlta Venice menu HUD:* live parameter values (`16 · Balanced`, `10 / 10`, `en-US`) displayed inline on every row so operators read state without opening sub-menus.
  3. *Android 15 System Settings (Pixel UI):* 48dp minimum touch targets on every button and segment pill, clear section headers, calm neutral dark surfaces, and directional chevrons that distinguish navigation rows from inline toggles.
- **Density / Voice / Motion personality:**
  - *Density:* Compact instrument deck (4dp base grid; 12dp–16dp internal card padding; 8dp row gaps within groups, 20dp between logical sections).
  - *Voice:* Plain, exact, sentence case. Zero hype words, zero exclamation marks, zero em dashes. Buttons are verbs that name the result (`Save keys`, `Done`, `Restart app`). Concurrency tiers use one unified 4-tier vocabulary (`Stable`, `Balanced`, `Fast`, `Max`) across rows, steppers, and preset chips.
  - *Motion:* Fast, interruptible 150 ms ease-out transitions on segmented selection, concurrency stepper/preset changes, and focus states; instant swap when system animations are disabled (`ANIMATOR_DURATION_SCALE == 0f`).
- **Budgets:**
  - Native Android View inflation ≤ 8 ms on mid-tier mobile; provider filter + summary calculation < 5 ms (measured ~0.02 ms in `StreamPlaySettingsUiUxSotaTest`); 0 network calls on settings open; 0 ms synchronous SharedPreferences + JVM property state switch; 14 active XML layouts and 4 active vector drawables (`0` unreferenced dead resources).
- **Kill list (Hard & Project Bans):**
  - Never use status pills/chips (`ENGLISH DEFAULT`, `URDU SOTA ACTIVE`, `INSTANT • 0ms`) in settings or media detail tags.
  - Never use colored edge accents or glowing colored card borders (`#3B82F6` / `#10B981` strokes around cards).
  - Never use decorative flag or status emoji (`🇬🇧`, `🇵🇰`, `🇺🇸`, `🇮🇳`, `✓`) in UI labels, buttons, media tags, or dialogs.
  - Never use em dashes (`—`) in UI copy, hints, toasts, dialogs, or comparison banners.
  - Never use engineering hype or internal jargon (`SOTA`, `Harvesters`, `0ms`, `Download Diversity`, `Neural fallback`, `JSON tiers`, `Balanced (Recommended)`, `Slow internet saver`, `Max speed`) in user-facing copy.
  - Never mix RTL Urdu script inside LTR parentheses without BiDi isolation (use `"Urdu \u2067\u0627\u0631\u062f\u0648\u2069"` without parentheses to prevent `Urdu) اردو)` inversion).
  - Never use identical gear icons on every row or a floppy-disk save icon for app restart; use inline value summaries + directional chevrons (`›`) for drill-down rows and a circular reload arrow (`save_icon.xml`) for app restart.
  - Never ship stock Material 1 primary colors (`#6200EE`, `#D32F2F`, `#4285F4`, `#2E7D32`) or Nord (`#2E3440`) on sub-dialogs, selectors, or compound button tints.

# Tokens (source of truth: `StreamPlay/src/main/kotlin/com/Phisher98/settings/StreamPlayDesignTokens.kt`)

| Token | OKLCH Target | Hex (sRGB) | Role & Exact Computed WCAG 2.2 Contrast |
|---|---|---|---|
| `surfaceCanvas` | `oklch(0.17 0.01 255)` | `#0E1116` | Dialog sheet background (cool obsidian neutral, not dead `#000` or `#1A1A1A`) |
| `surfaceCard` | `oklch(0.22 0.012 255)` | `#171C24` | Standard interactive row / card surface |
| `surfaceCardPressed` | `oklch(0.27 0.015 255)` | `#212834` | Pressed / active row surface |
| `surfaceTrack` | `oklch(0.15 0.01 255)` | `#0B0E13` | Inset segmented control track & input field well |
| `surfaceOverlay` | `oklch(0.25 0.014 255)` | `#1D2430` | Raised pill / secondary button surface |
| `borderSubtle` | `oklch(0.30 0.012 255)` | `#283140` | 1dp structural hairline separator / dialog frame |
| `borderFocus` | `oklch(0.92 0.01 255)` | `#E2E8F0` | 2dp TV D-pad & keyboard focus ring (`13.87:1` vs `surfaceCard`, `15.34:1` vs `surfaceCanvas`, passes WCAG non-text 3:1) |
| `textPrimary` | `oklch(0.96 0.005 255)` | `#F1F5F9` | Primary titles & active labels (`17.26:1` vs `surfaceCanvas`, `15.79:1` vs `surfaceCard`, WCAG AAA) |
| `textSecondary` | `oklch(0.74 0.015 255)` | `#94A3B8` | Supporting descriptions & inline row values (`6.67:1` vs `surfaceCard`, `7.29:1` vs `surfaceCanvas`, WCAG AA) |
| `textMuted` | `oklch(0.63 0.015 255)` | `#7C8B9E` | Section headers, input hints, disabled reasons (`5.44:1` vs `surfaceCanvas`, `5.56:1` vs `surfaceTrack`, WCAG AA) |
| `accentPrimary` | `oklch(0.58 0.17 255)` | `#2563EB` | Selected segment / primary action fill (`#FFFFFF` on-accent = `5.17:1` WCAG AA) |
| `accentSoft` | `oklch(0.80 0.09 255)` | `#93C5FD` | Inline value highlight on dark surface (`9.48:1` vs `surfaceCard`, WCAG AAA) |
| `dangerSurface` | `oklch(0.28 0.08 25)` | `#3B181E` | Destructive action surface (subtle, non-traffic-light) |
| `dangerText` | `oklch(0.80 0.11 25)` | `#FCA5A5` | Destructive action text (`8.29:1` vs `dangerSurface`, WCAG AAA) |

- **Type scale:** Dialog title `20sp` bold (`-0.01em` tracking) · Section eyebrow `12sp` medium sentence-case · Row title `15sp` semibold · Body/Segmented label `13sp` medium · Supporting metadata `12sp` regular (`1.4` line height).
- **Space & Radius scale:** Base `4dp` (`4, 8, 12, 16, 20, 24dp`). Dialog radius `20dp` · Card radius `14dp` · Segmented track radius `12dp` (`54dp` height) · Segment button radius `9dp` (`48dp` height; nested radius rule: `9dp + 3dp padding = 12dp`).
- **Touch, TV, RTL & Accessibility:** All interactive controls `>= 48dp` height; initial TV focus lands on `@id/subtitle_mode_pill_default` (`<requestFocus />`); RTL-aware horizontal D-pad focus links via `StreamPlayDesignTokens.applyDirectionalFocus`; `android:accessibilityLiveRegion="polite"` on dynamic status/count `TextView`s; explicit `compoundButtonTintList` (`#2563EB` checked / `#94A3B8` unchecked) and `switchThumbTintList` / `switchTrackTintList`.
