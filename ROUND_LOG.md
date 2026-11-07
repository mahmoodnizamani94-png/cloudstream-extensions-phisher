# Round Log

## Round 0: Orient, Observe & Baseline Critique (Director + Critic)

**Observed:** User-supplied Pixel portrait screenshot (`screenshots/round-0/baseline-device-pixel.png`) + XML/Kotlin audit across `MainSettingsFragment`, `LanguageSelectFragment`, `ConcurrencyBottomSheet`, `ProvidersFragment`, `WyzieSettingsFragment`, `ToggleFragment`, `StreamPlayStremioAddonFrag`, and `StreamPlayStremioCatelogFrag`.
**Scores:** Average **4.33 / 10** (Trust capped at **2/10** due to 7 §14.3 hard-ban hits; Typography **4/10** due to BiDi parenthesis corruption `Urdu) اردو • SOTA)` and 4-line title crush).
**Fixed:** None yet (Round 0 baseline orientation).
**Regressions:** none
**Decisions:** new `DEC-001`, `DEC-002`, `DEC-003`, `DEC-004` · locked none · challenged none
**DESIGN.md:** Created `v1` (Broadcast Control Deck thesis, OKLCH token table, 48dp touch floor, kill list).

---

## Rounds 1–3: Initial Token System, BiDi Isolation & Sub-Dialog Standardization

**Observed:**
- Established `StreamPlayDesignTokens.kt`, BiDi isolation (`\u2067...\u2069`), 48dp touch targets, and 3 section labels (`Subtitles & language`, `Playback & sources`, `Addons & extensions`).
- **Decisions:** `DEC-001`–`DEC-013`.

---

## Round 4 (Frontier Round 1 Overhaul): OS-Level Grouped Inset Surfaces, Lifecycle Hardening & Zero-Slop Polish

**Observed (Frontier Round 1 Council on `antigravity_2` · 3x `gemini-4-argon` `high`):**
- **Council Member 1 (OS Design Systems Architect):** **5.8 / 10** — Flagged the 8 individual floating bordered cards (`16` horizontal border lines), `#93C5FD` (`ACCENT_SOFT`) applied to every row value, `subtitle_mode_sources_chips` 2-line scraper dump, `WyzieSettingsFragment` rhythm broken by `Get free Wyzie key` + 50% red `Clear keys` button, and raw `AlertDialog.Builder` modals.
- **Council Member 2 (Interaction & Runtime Critic):** **5.5 / 10** — Flagged missing no-arg `DialogFragment` constructors causing `Fragment$InstantiationException` on rotation/fold, stacked-dialog dimmer multiplication when opening child `DialogFragment`s, `LanguageSelectFragment` duplicating the subtitle mode card and hijacking `UrduSubtitleEngine.setSubtitleMode`, missing `NestedScrollView` / IME insets on `concurrency_bottom_sheet.xml` and `streamio_addon_addlinks.xml`, and 13 raw `AlertDialog.Builder` escapes.
- **Council Member 3 (Visual Craft & Anti-Slop Auditor):** **6.2 / 10** — Flagged `"6 of 6 sources ready · Streams and downloads"` overflowing `320dp` width, ornamental narration (`"· Saved automatically"`, `"Always enabled (primary source)"`, `"Optional"`, `"Catalog"` repeated on every catalog row), mixed `"10 / 10"` vs `"2 of 2"` ratio formats, dead `settings_icon.xml`, and renderer discrepancies in `scripts/render_settings_screenshots.py`.

**Fixed in Round 4:**
- `F1` (OS Grouped Inset Sections): Replaced the 8 individual bordered cards in `fragment_main_settings.xml` / `MainSettingsFragment.kt` with 3 unified `groupedSectionDrawable` containers (`section_subtitles_group`, `section_playback_group`, `section_addons_group`) separated by `1dp` `#283140` hairlines inset `16dp` from start, using position-aware `groupedRowDrawable(density, GroupPosition)`. Applied the same grouped inset pattern to `ProvidersFragment`, `ToggleFragment`, `LanguageSelectFragment`, `StreamPlayStremioAddonFrag`, and `StreamPlayStremioCatelogFrag`.
- `F2` (Calm Secondary Row Values): Demoted right-hand row summaries from bright `#93C5FD` to `#94A3B8` (`TEXT_SECONDARY`) and `#7C8B9E` (`TEXT_MUTED` for `"None"`), reserving `#2563EB` strictly for the active subtitle segment pill and primary action buttons.
- `F3` & `F4` (Zero Overflow, Zero Narration Slop, Unified Ratios): Removed `subtitle_mode_sources_chips` from `fragment_main_settings.xml`, placed concise `"5 of 6 sources"` / `"6 of 8 sources"` inline in the hero header row (zero `320w` overflow), removed `"· Saved automatically"` / `"Always enabled (primary source)"` / `"Optional"`, hid `tvType` in `StreamPlayStremioCatelogFrag`, and unified all ratios to `"X of Y"` (`"10 of 10"`, `"2 of 2"`, `"2 of 3"`).
- `F5` (Rotation & Multi-Window Safe `DialogFragment`s): Added public no-arg `constructor()` to all 8 `DialogFragment` classes backed by `StreamPlayPlugin.instance` and `StreamPlayDesignTokens.resolvePluginResources`/`resolveSharedPreferences`, verified by reflection in `StreamPlaySettingsUiUxSotaTest`. Coordinated parent/child dialog visibility via `FragmentResultListener` (`FRAGMENT_RESULT_SETTINGS_CHANGED`).
- `F6` (Zero Raw `AlertDialog.Builder` Escapes): Replaced all 13 raw `AlertDialog.Builder` calls across `MainSettingsFragment`, `ProvidersFragment`, `WyzieSettingsFragment`, `StreamPlayStremioAddonFrag`, and `StreamPlayStremioCatelogFrag` with tokenized `StreamPlayDesignTokens` modals (`createThemedCustomDialog`, `showConfirmationDialog`, `showTextInputDialog`, `showSelectionListDialog`).
- `F7` (Single-Purpose `LanguageSelectFragment`): Removed `lang_subtitle_mode_banner`, added `btn_lang_done` header button, removed the conflicting `onStart` `MATCH_PARENT` override, and stopped overwriting `UrduSubtitleEngine.setSubtitleMode`.
- `F8` (Sub-Dialog Action Hierarchy): Moved `Get Wyzie key` into the `WyzieSettingsFragment` header row, demoted `Clear` to a quiet `SECONDARY` button enabled only when keys are present (with confirmation dialog), styled `ProvidersFragment` bulk/preset buttons as quiet `SECONDARY` pills, and hid `Load preset` / `Delete preset` when 0 presets exist.
- `F9` (Landscape `360dp` & IME Safety): Wrapped `concurrency_bottom_sheet.xml`, `wyzie_settings_fragment.xml`, and `streamio_addon_addlinks.xml` in `NestedScrollView`, added `SOFT_INPUT_ADJUST_RESIZE`, and included `WindowInsetsCompat.Type.ime()` in `applyWindowInsetsPadding`.
- `F10` (Honest Fail-Loud Renderer & Drawable Cleanup): Deleted unused `settings_icon.xml` (`3` active drawables remaining), tokenized `delete_icon.xml` to `#94A3B8` (`20dp`), and updated `scripts/render_settings_screenshots.py` to fail loud on missing XML IDs and render `screenshots/round-4/` (including `landscape-concurrency-640x360.png`, `LanguageSelectFragment`, and the real `delete_icon.xml` vector path).

**Regressions:** none
**Decisions:** new `DEC-014`, `DEC-015`, `DEC-016` · locked `DEC-001`–`DEC-016`
**DESIGN.md:** Updated to `Version 4 · revised round 4`.
**AI-tell scan:** **PASS (0 hard-ban hits, 0 substitute clichés, 0 raw `AlertDialog.Builder` calls across all 14 layouts, 3 drawables, 8 settings Kotlin files, and 5 provider/engine files; verified by 13 JUnit tests in `StreamPlaySettingsUiUxSotaTest` and 615 total unit tests in `:StreamPlay:testDebugUnitTest`)**.

---

## Round 5 (Frontier Round 2 Overhaul): System-Wide Header/List Divider Parity, Dynamic Window Sizing & Sub-Dialog Rotation Persistence

**Observed (Frontier Round 2 Council on `antigravity_2` · 3x `gemini-4-argon` `high`):**
- **Council Member 3 (Visual Craft & Anti-Slop Auditor):** **10 / 10 (TRUE STATE OF THE ART)** — Zero visual or anti-slop objections remaining.
- **Council Member 2 (Interaction & Runtime Critic):** **9.2 / 10** — Noted `createThemedCustomDialog` `onShowListener` overwrite in `showTextInputDialog`/`showSelectionListDialog`, fixed pixel height lock in `configureDialogWindow`, post-rotation parent window visibility when a child `DialogFragment` is open, filtered `ProvidersFragment` divider/focus/corner-radius recalculation, sub-dialog rotation persistence, and `!isInTouchMode` guard on `showSelectionListDialog`.
- **Council Member 1 (OS Design Systems Architect):** **9.1 / 10** — Noted missing `1dp` `#283140` `16dp`-inset dividers in `RecyclerView` grouped lists and programmatic modals, missing `1dp` `#283140` header dividers in 4 sub-dialogs, `16dp` vs `20dp` outer padding on `fragment_main_settings.xml`, `streamio_addon_addlinks.xml` button/label/background parity, and `scripts/render_settings_screenshots.py` string parity (`"2"` vs `"2 linked"`, single-string `RadioButton` in `LanguageSelectFragment`).

**Fixed in Round 5:**
- Resolved all `R2-1` through `R2-8` findings across `StreamPlayDesignTokens.kt`, all 8 settings `DialogFragment`s, all 9 modal/sheet XML layouts, `item_language.xml`, `item_provider_checkbox.xml`, `scripts/render_settings_screenshots.py`, and `StreamPlaySettingsUiUxSotaTest.kt` (now 15 JUnit tests in `StreamPlaySettingsUiUxSotaTest` and 617 total unit tests in `:StreamPlay:testDebugUnitTest`).
- **Decisions:** new `DEC-017`, `DEC-018` · locked `DEC-001`–`DEC-018`
- **DESIGN.md:** Updated to `Version 5 · revised round 5`.

