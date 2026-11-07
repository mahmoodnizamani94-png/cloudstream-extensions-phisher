Version 4 · revised round 4 (Frontier OS-Level Grouped Inset Overhaul)

# Design thesis

- **User:** CloudStream viewers on Android phones (one-handed portrait and landscape touch, OLED screens in dim rooms) and Android TV / Google TV boxes (10-foot D-pad remote navigation). They open StreamPlay Settings to switch subtitle language between English and Urdu, tune provider concurrency, curate active sources, or add subtitle/addon credentials, then get back to watching within seconds.
- **Job:** Configure playback subtitles, source concurrency, active providers, and addon integrations with immediate effect and zero guesswork.
- **Hero moment:** One-tap switching between English and Urdu subtitles in the top grouped section: immediate visual state transition (150 ms spring-eased surface shift), haptic tick, live source-readiness summary (`6 of 6 sources` / `8 of 8 sources`), and instant application to the next stream or download without restarting the app.
- **Thesis:** *A quiet, high-contrast OS-level broadcast control environment that groups related settings into unified inset section surfaces, displays calm secondary values at a glance, and acts in one tap.*
- **Is:** Instrument-grade, cohesive OS-grouped, high-contrast, direct.
- **Is not:** Generic AI dashboard (no 8 floating bordered rectangles per screen), marketing banner (no self-congratulatory "SOTA" badges or bright `#93C5FD` blue on every row value), nested card maze (no boxes inside boxes with glowing borders), settings scavenger hunt (no blind rows hiding current values behind modals).
- **Anchors:**
  1. *Braun T1000 / Dieter Rams control panels:* hierarchy through typographic weight, unified section planes, and `1dp` inset hairline dividers rather than individual bordered cards or colored status lights.
  2. *Sony CineAlta Venice / iOS & tvOS Grouped Inset Settings:* live parameter values (`16 · Balanced`, `10 of 10`, `2 of 2`, `2 of 3`, `en-US`) displayed inline in calm secondary text (`#94A3B8`) so the primary `#2563EB` accent is reserved exclusively for active selection and primary CTAs.
  3. *Android 15 System Settings (Pixel UI):* 48dp minimum touch targets on every button and segment pill, clear section headers, calm neutral dark surfaces, rotation/IME-safe `DialogFragment` lifecycle, and directional chevrons that distinguish navigation rows from inline toggles.
- **Density / Voice / Motion personality:**
  - *Density:* Compact OS grouped inset deck (4dp base grid; 16dp horizontal card padding; `0dp` inter-row gap inside grouped section containers separated by `1dp` `#283140` hairlines inset `16dp` from start; `20dp` between logical sections).
  - *Voice:* Plain, exact, sentence case. Zero hype words, zero exclamation marks, zero em dashes, zero ornamental state narration (`"Saved automatically"`, `"Always enabled (primary source)"`, `"Optional"`). Ratios use one unified format (`"10 of 10"`, `"2 of 2"`, `"2 of 3"`, `"6 of 6 sources"`). Buttons are verbs that name the result (`Save keys`, `Done`, `Restart app`). Concurrency tiers use one unified 4-tier vocabulary (`Stable`, `Balanced`, `Fast`, `Max`) across rows, steppers, and preset chips.
  - *Motion:* Fast, interruptible 150 ms ease-out transitions on segmented selection, concurrency stepper/preset changes, and focus states; instant swap when system animations are disabled (`ANIMATOR_DURATION_SCALE == 0f`).
- **Budgets:**
  - Native Android View inflation ≤ 8 ms on mid-tier mobile; provider filter + summary calculation < 5 ms (measured ~0.02 ms in `StreamPlaySettingsUiUxSotaTest`); 0 network calls on settings open; 0 ms synchronous SharedPreferences + JVM property state switch; 14 active XML layouts and 3 active vector drawables (`chevron_right_icon.xml`, `delete_icon.xml`, `save_icon.xml`; `0` unreferenced dead resources).
- **Kill list (Hard & Project Bans):**
  - Never render 8 individual floating bordered cards in a single vertical list; group related rows into unified `groupedSectionDrawable` (`#171C24`, `14dp` radius, `1dp` `#283140` border) containers with `1dp` inset hairline dividers and `groupedRowDrawable(density, GroupPosition)`.
  - Never use `#93C5FD` (`accentSoft`) on every right-hand row summary or dialog subtitle; use `#94A3B8` (`textSecondary`) for configured values and `#7C8B9E` (`textMuted`) for `"None"`, reserving `#2563EB` (`accentPrimary`) for the active segment pill and primary CTA buttons.
  - Never use ornamental state narration (`"· Saved automatically"`, `"Always enabled (primary source)"`, `"Optional"`, or repeating `"Catalog"` on every row of the catalog manifests list).
  - Never escape the StreamPlay OS design language with raw `android.app.AlertDialog.Builder` or `androidx.appcompat.app.AlertDialog.Builder` chrome; use `StreamPlayDesignTokens.createThemedCustomDialog`, `showConfirmationDialog`, `showTextInputDialog`, and `showSelectionListDialog`.
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
| `surfaceCard` | `oklch(0.22 0.012 255)` | `#171C24` | Unified grouped section container surface (`groupedSectionDrawable`) |
| `surfaceCardPressed` | `oklch(0.27 0.015 255)` | `#212834` | Pressed / D-pad focused grouped row surface (`groupedRowDrawable`) |
| `surfaceTrack` | `oklch(0.15 0.01 255)` | `#0B0E13` | Inset segmented control track & input field well |
| `surfaceOverlay` | `oklch(0.25 0.014 255)` | `#1D2430` | Raised pill / secondary button surface |
| `borderSubtle` | `oklch(0.30 0.012 255)` | `#283140` | 1dp structural hairline separator & grouped section frame |
| `borderFocus` | `oklch(0.92 0.01 255)` | `#E2E8F0` | 2dp TV D-pad & keyboard focus ring (`13.87:1` vs `surfaceCard`, `15.34:1` vs `surfaceCanvas`, passes WCAG non-text 3:1) |
| `textPrimary` | `oklch(0.96 0.005 255)` | `#F1F5F9` | Primary titles & active labels (`17.26:1` vs `surfaceCanvas`, `15.79:1` vs `surfaceCard`, WCAG AAA) |
| `textSecondary` | `oklch(0.74 0.015 255)` | `#94A3B8` | Supporting descriptions & calm inline row values (`6.67:1` vs `surfaceCard`, `7.29:1` vs `surfaceCanvas`, WCAG AA) |
| `textMuted` | `oklch(0.63 0.015 255)` | `#7C8B9E` | Section headers, input hints, unconfigured `"None"` values (`5.44:1` vs `surfaceCanvas`, `5.56:1` vs `surfaceTrack`, WCAG AA) |
| `accentPrimary` | `oklch(0.58 0.17 255)` | `#2563EB` | Selected segment / primary action fill (`#FFFFFF` on-accent = `5.17:1` WCAG AA) |
| `accentSoft` | `oklch(0.80 0.09 255)` | `#93C5FD` | Reserved accent highlight token (`9.48:1` vs `surfaceCard`, WCAG AAA; never applied to routine row values) |
| `dangerSurface` | `oklch(0.28 0.08 25)` | `#3B181E` | Destructive confirmation action surface (subtle, non-traffic-light) |
| `dangerText` | `oklch(0.80 0.11 25)` | `#FCA5A5` | Destructive confirmation action text (`8.29:1` vs `dangerSurface`, WCAG AAA) |

- **Type scale:** Dialog title `20sp` bold (`-0.015em` tracking) · Section eyebrow `12sp` bold sentence-case · Row title `15sp` bold · Body/Segmented label `13sp` bold · Supporting metadata `12sp` regular (`1.4` line height).
- **Space & Radius scale:** Base `4dp` (`4, 8, 12, 16, 20, 24dp`). Dialog radius `20dp` · Grouped section container radius `14dp` · Segmented track radius `12dp` (`54dp` height) · Segment button radius `9dp` (`48dp` height; nested radius rule: `9dp + 3dp padding = 12dp`).
- **Touch, TV, Lifecycle, RTL & Accessibility:** All interactive controls `>= 48dp` height; all 8 `DialogFragment`s expose a public no-arg constructor and resolve plugin `Resources`/`SharedPreferences` via `StreamPlayDesignTokens` so Android rotation/multi-window recreation never crashes; parent/child dialog visibility is coordinated via `FragmentResultListener` (`FRAGMENT_RESULT_SETTINGS_CHANGED`); initial TV focus lands on `@id/subtitle_mode_pill_default` (`<requestFocus />`); RTL-aware horizontal D-pad focus links via `StreamPlayDesignTokens.applyDirectionalFocus`; `android:accessibilityLiveRegion="polite"` on dynamic status/count `TextView`s; explicit `compoundButtonTintList` (`#2563EB` checked / `#94A3B8` unchecked) and `switchThumbTintList` / `switchTrackTintList`.
