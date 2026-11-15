# Pre-Change Empirical Baseline Metrics (Issue #17)

Captured on unmodified `feat/urdu-bolchal-audio-sync-17` (`cloudstream-extensions-phisher @ a580645`, `cloudstream @ 1b6c5ae5`) prior to Phase C edits.

## 1. Host Synchronization & Ranking Baseline (`cloudstream`)

| Benchmark / Check | Pre-Change Result | Target (`P_sync`) |
|---|---|---|
| Regime 1 (`-900 ms` lip-sync lag) | `verdict=IN_SYNC`, median error = **`900 ms`** (ignored by `1,500 ms` deadband) | `<= 50 ms` median, `<= 90 ms` P95 |
| Regime 2 (`+65,000 ms` cold-open / OP jump) | `verdict=LOW_CONFIDENCE`, median error = **`65,000 ms`** (exceeds `45,000 ms` cap) | `<= 50 ms` median, `<= 90 ms` P95 |
| Regime 3 (`25 -> 23.976` PAL speedup over 2h) | `verdict=LOW_CONFIDENCE`, median error = **`127,793 ms`** (rejected by `hasStableHalfOffsets`) | `<= 50 ms` median, `<= 90 ms` P95 |
| Regime 4 (`0 / +9.2s / +19.4s` commercial splits) | `verdict=LOW_CONFIDENCE`, median error = **`9,200 ms`** (constant-offset model) | `<= 50 ms` median, `<= 90 ms` P95 |
| Regime 5 (Sparse 16 kHz audio PCM VAD) | **Absent** (`0` audio decoders / `0` live `AudioSink` taps; `seekTime(-1)` flushes decoder) | `<= 50 ms` median, `0` `seekTime(-1)` flushes |
| `UrduAI Wyzie` provenance decoding | `key="wyzie"`, `priority=110` (machine translation impersonates top human catalogue) | `key="urdu-neural-fallback:wyzie"`, `OriginRankBand.BOLCHAL_FROM_HUMAN_CATALOGUE` |
| Movie/TV download candidate top order | `[wyzie, vidlink]` (unverified catalogue outranks stream-synced `VidLink`) | `[vidlink, wyzie]` (`StreamProvider` outranks `HumanCatalogue`) |

## 2. Extension Spoken Bol-Chal Urdu Baseline (`cloudstream-extensions-phisher`)

| Check | Pre-Change Result | Target (`P_lang`) |
|---|---|---|
| Pre-translation `adaptEnglishForSpokenUrduTranslation("What the hell, hold on, you're kidding me!")` | `"آخر یہ کیا, hold on, you're kidding me!"` (**`8` Nastaliq codepoints** spliced into `sl=en&tl=ur`) | **`0` Nastaliq codepoints** (`LatinEnglishText`) |
| Modern Google Translate literal calque dump (`"دائیں۔ جرمانہ۔ ٹھنڈا! شہد، آپ کا استقبال ہے، اس کی طرف سے مقدس گندگی کی گئی۔"`) | `isBrokenSmtUrdu=false`, `naturalnessScore=0` (**admitted as human**) | **Hard-rejected** (`mtDumpScore >= 60`) |
| Twist-reveal ellipsis split (`"I was forced to..."` / `"kill my own father."`) | Cue 1 = `"مجھے اپنے باپ کو قتل"` (**twist spoiled in Cue 1** via character-ratio SOV slicing) | **1-to-1 `CueId` mapping** (`0%` twist inversion) |
| Public SubtitleCat CDN upload (`publishToSubtitleCatCdn`) | Present in both `UrduSubtitleEngine.kt:2048` and `UrduSubtitleTranslator.kt` | **Deleted** (`0` CDN uploads) |
