### Components Found

#### 0. Baseline Audit of Current Codebase vs. 2026 SOTA
* **Extension Harvester & Idiom/NMT Engine** — [UrduSubtitleEngine.kt:65](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:65):
  * Harvests 8 Urdu catalogues, normalizes CP1256/UTF-16/Arabic presentation forms ([UrduSubtitleEngine.kt:958](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:958)), verifies Urdu vs. Persian/Arabic on raw pre-normalized orthography ([UrduSubtitleEngine.kt:2290](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2290)), and wraps lines in `\u200F` RLM ([UrduSubtitleEngine.kt:1146](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1146)).
  * **Gaps**: (1) Zero timeline/audio synchronization inside the extension — off-sync SubDL/Subf2m/OpenSubtitles human `.srt` files are materialized as-is; (2) [isBrokenSmtUrdu](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1321) only catches 2012-era phrase-SMT with embedded ASCII (`"کے ripping کے"`), letting 100%-Urdu-script Google Translate dumps pass `materializeNormalizedUrduSubtitle` ([UrduSubtitleEngine.kt:2494](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2494)) as verified "Tier-1 Human" tracks; (3) [buildContextualTranslationUnits](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1773) + [splitTranslatedUrduProportionally](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1677) merges 2–3 English SVO cues and slices the Urdu SOV sentence by English character lengths, inverting clause/reveal timing.
* **Host Neural Translator** — [UrduSubtitleTranslator.kt:35](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/UrduSubtitleTranslator.kt:35):
  * Translates up to `BATCH_SIZE = 250` cues via `clients5`/`translate.google.com`/`gtx` multi-`q` or delimiter batches with bisect fallback ([UrduSubtitleTranslator.kt:658](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/UrduSubtitleTranslator.kt:658)) and `evaluateTranslationQuality` ([UrduSubtitleTranslator.kt:397](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/UrduSubtitleTranslator.kt:397)).
  * **Gaps**: Pure stateless cue-by-cue NMT with zero scene context, zero English/Urdu spoken idiom adaptation (unlike `UrduSubtitleEngine`), `CPS > 32.0` error threshold instead of `17.0` AVT comfort ceiling, and no speaker gender or `آپ`/`تم`/`تو` register consistency.
* **Host Timeline Verifier & Renderer Stack** — [SubtitleSyncAndManifest.kt:31](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt:31), [FixedNextRenderersFactory.kt:12](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/FixedNextRenderersFactory.kt:12), [CS3IPlayer.kt:882](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:882), [CustomSubtitleDecoderFactory.kt:331](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt:331):
  * `SubtitleSyncEngine` only runs during offline downloads ([DownloadManager.kt:10359](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt:10359)), uses `BIN_SIZE_MS = 250L`, `OVERLAP_TOLERANCE_MS = 1_500L`, `MIN_CONSTANT_SHIFT_MS = 1_500L` (ignoring `< 1.5s` lip-sync lag), immediately gives up when `anchorVtt == null` ([SubtitleSyncAndManifest.kt:210](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt:210)), explicitly refuses FPS-drifted tracks via `hasStableHalfOffsets` ([SubtitleSyncAndManifest.kt:275](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt:275)), and has zero piecewise split DP or audio VAD.

---

#### 1. Angle 1 — Exact `alass` $O(1)$-Amortized Piecewise Split + FPS-Drift Algorithm
In `kaegi/alass` (`alass-core`), subtitle synchronization is formulated over discrete integer ticks $\Delta t = 10\text{ ms}$ (or $20\text{ ms}$ coarse pass), mapping reference spans $R = \{[a_j, b_j)\}_{j=1}^M$ and candidate subtitle spans $S = \{[s_i, e_i)\}_{i=1}^N$ over a bounded offset search grid $o \in [-O_{\max}, +O_{\max}]$ of size $K = 2 O_{\max} + 1$ (for $\pm 120\text{ s}$ at $\Delta t = 10\text{ ms}$, $K = 24{,}001$, so one `FloatArray(K)` is **96 KB** and stays L2-cache resident).

1. **Piecewise-Linear Overlap Trapezoid & Second-Derivative (`RatingDeltaDelta`) $O(1)$ Construction**:
   * For candidate span $s_i = [s_i, e_i)$ ($L_s = e_i - s_i > 0$) shifted by $o$ and reference span $r_j = [a_j, b_j)$ ($L_r = b_j - a_j > 0$), raw overlap is $\text{ov}(o) = \max(0, \min(e_i + o, b_j) - \max(s_i + o, a_j))$, which is a trapezoid with 4 knots:
     $$k_1 = a_j - e_i,\quad k_2 = \min(a_j - s_i,\, b_j - e_i),\quad k_3 = \max(a_j - s_i,\, b_j - e_i),\quad k_4 = b_j - s_i$$
   * **Scoring weights**:
     * `standard_scoring`: slope $\sigma = \frac{1}{\max(L_s, L_r)}$, giving plateau height $\sigma \cdot \min(L_s, L_r) = \frac{\min(L_s, L_r)}{\max(L_s, L_r)} \in (0, 1]$ ($1.0$ iff $L_s = L_r$; optimal for subtitle-to-subtitle alignment and piecewise DP where `split_penalty` is denominated in cue-equivalents).
     * `overlap_scoring`: slope $\sigma = \frac{1}{L_s}$ (or unit tick overlap $\sigma = 1$), giving plateau $\frac{\min(L_s, L_r)}{L_s}$ so a VAD span that splits or merges spoken clauses is not penalized for length mismatch during FPS ratio search.
   * **`RatingDeltaDelta` ($O(1)$ per overlapping pair)**: Instead of updating $O(K)$ offsets per pair, accumulate second differences into `dd = FloatArray(K)` (shifting indices by $+O_{\max}$, accumulating boundary value $v_0$ and initial slope $d_0$ at index $0$ when $k_m + O_{\max} < 0$):
     $$\text{dd}[k_1 + O_{\max}] \mathrel{+}= \sigma,\quad \text{dd}[k_2 + O_{\max}] \mathrel{-}= \sigma,\quad \text{dd}[k_3 + O_{\max}] \mathrel{-}= \sigma,\quad \text{dd}[k_4 + O_{\max}] \mathrel{+}= \sigma$$
     Recover the exact rating array across all $K$ offsets via one double prefix sum in $O(K)$:
     $$d \mathrel{+}= \text{dd}[k],\qquad v \mathrel{+}= d,\qquad \text{rating}_i[k] = v$$
   * For `align_nosplit` (global shift scoring across all $N$ cues), accumulate all pairs $(s_i, r_j)$ into a **single** `dd` buffer (`~48,000` 4-point updates for 1,200 cues) + 1 sweep $\implies$ **`< 0.4 ms` per FPS candidate** on Android ARM64.

2. **$O(1)$-Amortized Per-Offset Piecewise Split DP & Low-Memory Backtracking**:
   * **Causal Monotonicity Constraint**: With inter-cue gap $g_i = \max(0, s_i - e_{i-1})$, cue $i$ cannot invert before cue $i-1$: $e_{i-1} + o' \le s_i + o \iff o' \le o + g_i$.
   * **Split Transition with Jump Regularization**: With split cost $P(o', o) = P_{\text{split}} + \lambda |o - o'|$ ($\lambda = 2 \times 10^{-4}$ per $10\text{ ms}$ tick breaks silence ties in favor of minimal jump):
     * Left envelope ($o' \le o$): single forward pass $L[k] = \max(DP_{\text{prev}}[k],\; L[k-1] - \lambda)$.
     * Right bounded deletion window ($o < o' \le \min(K-1, o + g_i)$): single backward **monotonic deque** maximizing $(DP_{\text{prev}}[k'] - \lambda k')$ over window width $g_i$, giving $R_{\text{win}}[k] = \text{dequeMax}(k) + \lambda k$ in exact amortized $O(1)$ per offset.
     * Recurrence ($O(K)$ per cue, zero $O(K^2)$ loops):
       $$DP[k] = \text{rating}_i[k] + \max\!\Big(DP_{\text{prev}}[k],\; L[k] - P_{\text{split}},\; R_{\text{win}}[k] - P_{\text{split}}\Big)$$
   * **Memory Compression (`< 500 KB` vs. `115 MB`)**: Never allocate `FloatArray(N * K)`. Store `DP` in two rolling `FloatArray(K)` buffers (`prev`, `curr`), and record per-cue backpointers as **run-length encoded intervals** `(startK: Short, endK: Short, prevK: Short)` only where a split beats `DP_prev[k]` (typically `< 24` runs/cue $\approx 350\text{ KB}$ for $N = 1{,}200$).
   * **Exact Parameters**:
     * `split_penalty`: $P_{\text{split}} = 7.0$ (`standard_scoring` units) for subtitle-to-subtitle; $P_{\text{split}} = 8.5$ for dense/multi-window VAD (higher acoustic noise floor); **edge multiplier** $1.35 \times P_{\text{split}}$ in the first/last $5\%$ of cues so an 8-cue `"Previously on..."` recap or `"Next time on..."` preview cannot split onto random dialogue for the price of a single boundary split.
     * **FPS Candidates**: Test all 9 exact ratios $\mathcal{F} = \{1.0,\; \frac{24}{23.976024},\; \frac{23.976024}{24},\; \frac{25}{23.976024},\; \frac{23.976024}{25},\; \frac{25}{24},\; \frac{24}{25},\; \frac{29.97003}{23.976024},\; \frac{23.976024}{29.97003}\}$ via `align_nosplit` (`overlap_scoring`, `< 4 ms` total) BEFORE piecewise DP, applying a $-0.015$ unity prior penalty to $\alpha \ne 1.0$. Pass both $\alpha = 1.0$ and the top $\alpha \ne 1.0$ into piecewise DP so videos having **both** PAL speedup and commercial breaks still solve cleanly; otherwise `alass` DP approximates `24/23.976` (`3.6s/hr`) with staircase splits that leave `±450 ms` intra-segment lip-sync error.
     * **Sub-10ms Onset Polish & Nose/Tail Trimming**: Within each continuous DP segment of $\ge 12$ cues, refine $o_k$ via median onset residual `(vadOnsetMs - 35ms) - scaledCueStartMs` (using speech *onsets* rather than hangover-smeared speech *ends*, with a `35ms` Netflix-standard pre-speech visual lead). Clamp leading/trailing blocks whose per-cue match score $< 0.35$ to the nearest high-confidence interior segment's affine map, dropping cues shifted to $e'_i \le 0$ or $s'_i \ge T_{\text{video}}$.

3. **Three Reference-Source Adaptations**:
   * **(a) Frame-Locked Video-Provider Sidecar Reference**: Dense full-timeline cues (`vidlink`, `meowtv`, `cineby`, `cinesrc`, `anizone`). Run 9-FPS `align_nosplit` + top-2 piecewise DP (`dt = 10ms`, `standard_scoring`, `P_split = 7.0`, `±120s`). Completes in **`12–25 ms`**.
   * **(b) Sparse Multi-Window Audio VAD (`4–6` Windows in `< 800 ms`)**:
     * **Observation-Masked Zero-Mean Scoring**: Standard `alass` fails on sparse windows $\mathcal{W} = \bigcup_{m=1}^5 [u_m, v_m]$ because outside $\mathcal{W}$ reference VAD is empty, incentivizing DP splits that drag unobserved cues into $\mathcal{W}$. Fix: score $+w$ for speech overlap inside $\mathcal{W}$, $-0.25 w$ for confirmed-silence overlap inside $\mathcal{W}$, and **exact $0$ outside $\mathcal{W}$**. Only cues overlapping $\mathcal{W}$ after shift $(\alpha, o)$ can earn or lose score; splits across unobserved regions gain $0$ and lose $P_{\text{split}}$.
     * **Window Placement**: Movies (`5 × 25s` = `125s` audio at `4%, 18%, 42%, 68%, 88%` of duration); 24-min Anime (`5 × 35s` at `[0..35s]` cold-open, `[110..150s]` post-OP, `[420..455s]` Act A, `[780..815s]` post-eyecatch Act B, `[1170..1210s]` pre-ED) — solves cold-open + `90s` cut/uncut OP split + eyecatch + FPS drift in `< 700 ms` via `MediaExtractor` + `MediaCodec` (local file or HTTP range/HLS segment fetch).
   * **(c) Progressive Live PCM Lookahead (`ForwardingAudioSink` + Zero-Flicker Convergence)**:
     * Tap decoded PCM + exact `presentationTimeUs` via `ForwardingAudioSink.handleBuffer` inside `buildAudioSink` (only sampling when `super.handleBuffer(...) == true`).
     * Re-run incremental `alass` over `[0, maxObservedPtsMs]` after initial `8` speech spans and every `3.0 s` of new speech thereafter.
     * **Zero-Flicker Convergence**: Replace `CS3IPlayer.setSubtitleOffset`'s destructive `seekTime(-1)` ([CS3IPlayer.kt:884](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:884)) with a `DynamicSyncedSubtitle` wrapper in `DelegatingSubtitleDecoder` ([CustomSubtitleDecoderFactory.kt:426](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt:426)) obeying: (i) **Active-Cue Lock** (never alter `startTimeUs`/`endTimeUs` of a cue currently visible on screen or already passed), (ii) **`45 ms` Hysteresis Deadband**, and (iii) **Inter-Cue Silence Slewing** (`<= 35 ms` adjustment per silence gap for minor drift; instant step on future cues only when a split or initial lock exceeds `80 ms` with confidence $\ge 0.55$).

---

#### 2. Angle 2 — Lightweight, Noise-Robust Pure-Kotlin PCM Speech VAD
Operating on `16 kHz` mono PCM (`Δt = 10 ms` = `160` samples/step, `20 ms` = `320`-sample Hann window / `256`-pt in-place radix-2 real FFT, `< 0.03%` single-core CPU, zero heap allocation after init):

1. **Spatial Pre-Filtering (Before Mono Downmix)**:
   * **5.1 / 7.1 Surround (`C >= 6`)**: Extract `x_mono = 0.70f * FC + 0.15f * (FL + FR)` (`FC = ch[2]`, dropping `LFE`, `BL`, `BR`), yielding an immediate **+10–15 dB dialogue-to-explosion/music SNR gain**.
   * **Stereo (`C == 2`)**: Compute Mid $M = \frac{L+R}{2}$ and Side $S = \frac{L-R}{2}$ energy ratio $\rho_{\text{side}} = \frac{E_S}{E_M + \epsilon}$ per frame (centered dialogue: $\rho_{\text{side}} < 0.35$; wide stereo score/reverb/SFX: $\rho_{\text{side}} > 0.55$).
   * Decimate to `16 kHz` (`3:1` `[0.25, 0.5, 0.25]` FIR from `48 kHz`; linear fractional step from `44.1 kHz`).

2. **Pre-Emphasis + Exact `16 kHz` Speech-Formant Biquad Cascade (`300–3400 Hz`)**:
   * Pre-emphasis: $y[n] = x[n] - 0.97 x[n-1]$ (lifts $F_2/F_3$ formants in `1–3.4 kHz` against low-end rumble).
   * Exact Direct-Form-II Transposed 2nd-order Butterworth coefficients at $f_s = 16{,}000\text{ Hz}$:
     * **HPF `300 Hz`**: $b_0 = 0.920066,\; b_1 = -1.840132,\; b_2 = 0.920066,\; a_1 = -1.833733,\; a_2 = 0.846532$.
     * **LPF `3400 Hz`**: $b_0 = 0.227118,\; b_1 = 0.454236,\; b_2 = 0.227118,\; a_1 = -0.276665,\; a_2 = 0.185136$.

3. **Multi-Feature Speech vs. Music/Explosion/OP Discriminators (per `10 ms` frame)**:
   * **Sub-Band Energies & Spectral Flatness (`256`-pt Real FFT on pre-emphasized `16 kHz`, $\Delta f = 62.5\text{ Hz}$)**:
     * Speech band $E_{\text{sp}} = \sum_{k=5}^{54} P[k]$ (`312–3375 Hz`), Low rumble $E_{\text{lo}} = \sum_{k=1}^{4} P[k]$ (`62–250 Hz`), High sibilance $E_{\text{hi}} = \sum_{k=56}^{115} P[k]$ (`3.5–7.2 kHz`).
     * Wiener Spectral Flatness over speech bins: $\text{SFM}[t] = \frac{\exp\left(\frac{1}{50}\sum_{k=5}^{54}\ln(P[k]+\epsilon)\right)}{\frac{1}{50}\sum_{k=5}^{54}P[k]+\epsilon}$. Broadband explosions/gunfire/wind/rain yield $\text{SFM} > 0.50$ (and $E_{\text{lo}} > 2.5 E_{\text{sp}}$ on blasts); harmonic voiced vowels/formants yield $\text{SFM} \in [0.04, 0.38]$.
   * **Rainer Martin Minimum-Statistics Noise Floor (`8 × 200 ms = 1.6 s` Sub-Window Ring)**:
     * Smooth $E_{\text{dB}}[t] = 0.7 E_{\text{dB}}[t-1] + 0.3 \cdot 10\log_{10}(E_{\text{sp}}[t] + \epsilon)$; track minimum across 8 rolling `20`-frame buckets + bias compensation $+1.85\text{ dB}$ to get $N_{\text{floor}}[t]$ and posterior SNR $\gamma_{\text{dB}}[t] = E_{\text{dB}}[t] - N_{\text{floor}}[t]$. Rides through continuous speech via stop-consonant closures without adapting away dialogue.
   * **`4.0 Hz` Syllabic Modulation Resonator ($f_{\text{env}} = 100\text{ Hz}$, $Q = 2.0$, `2–6 Hz` passband)**:
     * Exact biquad on $E_{\text{dB}}[t]$: $b_0 = 0.058533,\; b_1 = 0,\; b_2 = -0.058533,\; a_1 = -1.823778,\; a_2 = 0.882933$.
     * Syllabic modulation depth $M_{4\text{Hz}}[t]$ (`500 ms` RMS of resonator output) peaks on `3–5 syllable/s` human speech (`>= 1.8 dB`), rejecting sustained orchestral strings, synth drones, and steady ambient noise (`< 0.7 dB`).
   * **False-Positive Pitfalls & Countermeasures**:
     * *Anime OP/ED & Sung Vocals*: Singing has human formants + low SFM and **will** trigger frame-level VAD. Countermeasure: track rolling `5.0 s` active duty cycle + stereo $\rho_{\text{side}}$; sung choruses with accompaniment exhibit `dutyCycle > 0.86 && meanSideRatio > 0.40` (whereas spoken dialogue has `20–45%` stop/breath gaps), down-weighting span confidence by `0.25x` so `alass` never prefers OP vocals over dialogue.
     * *Unvoiced Plosives/Fricatives (`[s, f, ʃ, t, k]`)*: High ZCR (`0.22..0.55`) and elevated SFM (`0.45..0.68`) at word edges; allow SFM up to `0.68` during the `150 ms` hangover state when $E_{\text{hi}} > E_{\text{lo}}$ so word-initial *"S-"* isn't clipped.
   * **State Machine**: Attack = `2` consecutive frames (`20 ms`) with $\gamma_{\text{dB}} \ge 7.5\text{ dB}$, $\text{SFM} \le 0.45$, $E_{\text{lo}} < 3.0 E_{\text{sp}}$; Hangover = `15` frames (`150 ms`) at $\gamma_{\text{dB}} \ge 4.0\text{ dB}$; subtract `120 ms` hangover tail on release, merge gaps `< 110 ms`, drop spans `< 90 ms`.

---

#### 3. Angle 3 — Detecting Modern Google Translate / Literal MT Dumps in Urdu Catalogues
Because modern NMT dumps contain clean Urdu script with zero embedded English, replace the binary `isBrokenSmtUrdu` gate in [UrduSubtitleEngine.kt:2494](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2494) with a composite `UrduMtDumpDetector` scoring `mtDumpScore` across 5 orthogonal signal families:

1. **Zero-Context Polysemy & Idiom Calques (High-Precision NMT Giveaways, `+18..+30` pts each)**:
   * Standalone cue sense errors: `^\s*[\u200F]*دائیں[۔.!؟?]*\s*$` (*"Right."* $\to$ spatial right), `^\s*[\u200F]*جرمانہ[۔.!؟?]*\s*$` (*"Fine."* $\to$ monetary fine), `^\s*[\u200F]*ٹھنڈا[!۔.]*\s*$` (*"Cool!"* $\to$ cold temperature), `^\s*[\u200F]*بطخ[!۔.]*\s*$` (*"Duck!"* $\to$ waterfowl), vocative `شہد[،,]` (*"Honey,"* $\to$ bee honey), `میرا برا` (*"My bad"*), `کوئی جرم نہیں` (*"No offense"*).
   * Phrase calques: `آپ کا استقبال ہے` (*You're welcome*), `اوہ\s*[،,]?\s*میرے\s+خدا` (*Oh, my God*), `اپنے دماغ سے باہر` (*out of your mind*), `کوئی راستہ نہیں` (*No way*), `(?:مقدس|بیل کی)\s+گندگی` (*holy shit / bullshit*), `چلو پر` (*come on*), and mechanical overuse of `حاصل کرنا / حاصل کیا` for English *get/got* (`> 1.2%` of cues).
2. **Syntactic & Newsroom Register Artifacts**:
   * **Agentive Passive `کی طرف سے`**: English `by`-passive calque (`X کی طرف سے مارا گیا / بھیجا گیا`); `> 1.2%` of cues flags MT (`+20` pts).
   * **Indefinite Predicate Copula (`یہ ایک ... ہے` / `وہ ایک ... ہے`)**: English *"It is a..." / "He is a..."* calque where native Urdu omits `ایک`; rate `> 2.8%` of cues flags MT (`+18` pts; human drama baseline `< 0.6%`).
   * **Adjacent-Cue Pronoun Whip-Lash (`آپ` $\leftrightarrow$ `تم`/`تو`)**: Measure 2nd-person pronoun flips (`آپ`/`کریں`/`ہیں` vs `تم`/`کرو`/`ہو` vs `تو`/`تیرا`) across consecutive cues with `gapMs < 1800` and no speaker-turn dash (`- `). Stateless line-by-line Google Translate exhibits `flipRate > 0.28` (`+25` pts) vs `< 0.08` in human tracks.
3. **Cross-Cue Continuation Shattering & English Length Lock**:
   * When an English donor sidecar is available, compare cue count (`|N_ur - N_en| == 0` with character-length Pearson $r > 0.93$) AND check continuation cues where English Cue $i$ has no terminal punctuation (`"...shouldn't"`) while Urdu Cue $i$ ends in `۔` and Cue $i+1$ turns into a bare imperative (`"جاؤ۔"`).
4. **Spoken Bol-Chal Particle Deficit**:
   * Measure rate of authentic conversational Urdu particles/idioms (`نا\s*[؟!۔]?$`, `تو سہی`, `بھئی`, `یار`, `ارے`, `ابے`, `ذرا`, `خیر`, `بھلا`, `ویسے`, `حد ہو گئی`, compound verbs `کر بیٹھنا / دے مارنا / چلا جانا`). Across $\ge 200$ dialogue cues, `< 0.4%` particle incidence adds `+20` MT-dump points.
5. **Catalogue Metadata & Uploader Signals**:
   * Inspect OpenSubtitles `machine_translated` / `ai_translated` JSON flags, SubSource/Subf2m release comments (`Subtitle Edit`, `Google`, `Auto`, `AI`), SubtitleCat `-ur.srt` paths, and expand `SUBDL_TIER_1C_MACHINE_AUTHORS` ([UrduSubtitleEngine.kt:2821](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2821)).
   * **Routing**: `Authentic Human` (`mtScore < 30`) $\to$ Tier 1A primary; `Stiff/Edited MT` (`30..59`) $\to$ demote behind Bol-Chal Scene Translator if LLM is active, or refine + sync as backup; `Raw MT Dump` (`>= 60` or `isBrokenSmtUrdu`) $\to$ reject in favor of video-synced Tier-2 localization.

---

#### 4. Angle 4 — Scene-Windowed Bol-Chal Localization & Clause-Accurate Cue Timing
1. **2026 Dual-Tier Inference Architecture (Zero-Breakage Keyless + Configurable LLM)**:
   * **Tier 2A — Optional User-Configured OpenAI-Compatible / Gemini LLM Endpoint** (keys read via cross-module `SharedPreferences` just like `wyzie_api_key`/`subdl_api_key` in [UrduSubtitleEngine.kt:3069](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:3069)): Supports Gemini (`gemini-2.5-flash` / `gemini-3-flash` free/paid tier), Groq (`qwen3` / `llama-3.3-70b`), OpenRouter, DeepSeek, or local LAN Ollama. Sends **Scene Windows of `25–35` cues** split at silence gaps $\ge 2.5\text{ s}$, plus `3` read-only `[ctx_prev]` cues, `2` `[ctx_next]` cues, and a compact `SpeakerContextCard`.
   * **Tier 2B — Default Keyless Out-of-the-Box Pipeline (100% of Zero-Config Users)**:
     * Retains keyless Google NMT (`clients5` / `translate.google.com` / `gtx`) unified across both repos (sharing pre/post bol-chal idiom adaptation currently missing from `UrduSubtitleTranslator.kt`), with optional **Google ML Kit On-Device Translation (`com.google.mlkit:translate` `en->ur`)** in the host app as an official offline/rate-limit fallback.
     * Feeds `15–20`-cue scene paragraphs anchored with non-alphabetic immutable IDs (`⟦14⟧ ...`) so NMT's encoder attends across adjacent lines (`"Let's meet at 8."` + `"Fine."` $\to$ `"ٹھیک ہے۔"`, not `"جرمانہ۔"`), automatically bisecting back to 1-to-1 `q=` array on any dropped marker.
   * **Progressive Window-0 Streaming**: On live streams, translate **Window 0 (first 25 cues)** first (`< 500 ms` NMT / `< 1.1 s` Flash LLM) and emit immediately while remaining windows pipeline in parallel (`Semaphore(3)`), eliminating full-file startup latency.

2. **1-to-1 `[cue_id]` Timing Lock (Eliminating SVO $\to$ SOV Character-Ratio Twist Spoilers)**:
   * **Retire proportional character-length slicing** ([UrduSubtitleEngine.kt:1677](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1677)) across reveal ellipses (`...`) and multi-clause turns: merging `"The killer we've been hunting is..."` + `"...your brother!"` into SOV Urdu puts `"تمہارا بھائی"` in Cue 1, spoiling the reveal 2 seconds early.
   * **Cue-Locked Correlative Bol-Chal Mapping**: Every input `{id, dur_s, max_chars, en}` maps 1-to-1 to `{id, ur}` using spoken Urdu topic-comment / correlative (`جس ... وہ`, `بات یہ ہے کہ ...`) phrasing so each cue speaks only what the actor utters during that cue's exact `[startMs, endMs]`. For keyless NMT where 2 non-ellipsis cues split a bare `aux + verb` (`"shouldn't" | "go alone"`), split **only** at Urdu clause/postposition boundaries (`کہ`, `کیونکہ`, `لیکن`, `تو`, or after `نے/کو/سے/میں/پر`), never by English character ratio.

3. **Speaker Gender, `آپ` / `تم` / `تو` Register & Urban Code-Switching**:
   * **Scene `SpeakerContextCard`**: Track character names, inferred gender (`he/she`, kinship/title cues) for verb agreement (`رہا ہے` / `رہی ہے`), and dyadic T-V lock: **`تم`** default for peers/friends/spouses/anime classmates (replacing NMT's robotic default `آپ`), **`آپ`** for elders/strangers/authority, **`تو`** for street anger/intimacy — locked across consecutive cues within a scene to prevent pronoun whip-lash.
   * **Everyday English Code-Switching in Urdu Script**: Keep natural everyday spoken loanwords transliterated in Urdu script (`فون`, `میسج`, `کال`, `پولیس`, `ڈاکٹر`, `ہسپتال`, `آفس`, `میٹنگ`, `ٹیم`, `سسٹم`, `پاس ورڈ`, `سگنل`, `پلیز`, `سوری`, `باس`, `ایجنٹ`) paired with native Urdu postpositions/light verbs (`کال کرو`, `سسٹم ہیک ہو گیا`) instead of textbook calques (`ہاتف`, `برقی پیغام`, `شفا خانہ`), while avoiding raw Latin words that break RTL layout.

4. **Clause-Accurate Cue Timing & `CPS <= 17` Waterfall**:
   * Measure `visibleCps` after stripping `\u200F`/`\u200E`/`\u200C` and markup tags. When `visibleCps > 17.0`:
     1. **Gap Extension First**: Extend `endMs` into trailing inter-cue silence up to `min(nextStartMs - endMs - 84ms, 700ms)` (preserving a 2-frame `84 ms` blink gap) and `startMs` up to `-100 ms` into leading silence (`>= 833 ms` minimum cue floor). Resolves ~65% of high-CPS cues with zero splitting.
     2. **Bol-Chal Condensation**: Trim filler prefixes (`میں یہ کہہ رہا تھا کہ` $\to$ drop; `میرے خیال میں` $\to$ `لگتا ہے`).
     3. **Syntactic + VAD-Valley Sub-Cue Split (`visibleCps > 17.0 && durationMs >= 1800 ms`)**: Never split cues `< 1800 ms` (avoids `< 833 ms` unreadable flash-cues). Break at the central `[0.35..0.65]` punctuation (`،`, `—`), conjunction (`کہ`, `لیکن`, `مگر`, `کیونکہ`, `ورنہ`), or postposition-trailing boundary (never between noun and postposition `گھر | میں`), inserting an `80 ms` blink gap and snapping `t_split` to an intra-cue VAD breath valley if available, else weighting by Urdu phonological syllable weight.

---

### Flow

```
[Catalogue Urdu (.srt/.zip)] ──► Decode + Raw Urdu Gate ──► UrduMtDumpDetector
                                                               │
                 ┌───────────────────────(< 30: Tier 1A Human)─┴─(>= 60: MT Dump -> Reject)
                 ▼                                                               │
      Sync Target Candidate                                                      ▼
                 │                                       [Best Video-Anchored English Donor]
                 │                                                       │
                 │                                       Scene-Windowed [cue_id] Localizer
                 │                                       (Tier 2A LLM / Tier 2B Keyless NMT
                 │                                        + Register Lock + CPS<=17 Refiner)
                 │                                                       │
                 ▼                                                       ▼
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ Unified alass + VAD Synchronization Engine (dt = 10ms)                                   │
│  1. If Video-Provider Anchor Sidecar exists -> 9-FPS align_nosplit + Piecewise DP (20ms) │
│  2. Else Offline / Seekable Stream -> 5-Window Observation-Masked Sparse VAD (< 700ms)   │
│  3. Live Playback -> ForwardingAudioSink PCM Tap -> 16kHz Speech VAD -> DynamicSyncedSub │
│     (Active-Cue Lock + 45ms Hysteresis + Silence-Gap Slewing; ZERO seekTime(-1)!)        │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Offline Downloads** ([DownloadManager.kt:10359](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt:10359), [DownloadManager.kt:10660](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt:10660)):
   * Extract 5 sparse 25–35s PCM windows directly from the downloaded video file via `MediaExtractor` + `MediaCodec` (`< 600 ms`), run pure-Kotlin Speech VAD, and cross-verify/align *both* the anchor English donor and any Tier-1 human Urdu catalogue tracks via observation-masked `alass` (FPS + piecewise DP) before saving WebVTT sidecars and recording `syncConfidence`, `appliedOffsetMs`, `fpsRatio`, and `mtAuthenticityScore` in [SidecarManifestStore](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt:534).
2. **Live Streams** ([UrduSubtitleEngine.kt:2537](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2537), [RepoLinkGenerator.kt:679](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/RepoLinkGenerator.kt:679), [GeneratorPlayer.kt:1859](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt:1859), [CS3IPlayer.kt:1785](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:1785)):
   * Pre-align Tier-1 human Urdu tracks against any stream-synced provider English `.vtt` (`vidlink`/`meowtv`/`cineby`/`cinesrc`/`anizone`) in `< 25 ms` before emitting `file:///...`.
   * During playback, `ForwardingAudioSink` feeds `presentationTimeUs`-stamped PCM into the background `16 kHz` Speech VAD + incremental `alass` solver, driving `DynamicSyncedSubtitle` in `CS3IPlayer` for sub-100ms lip-sync even when zero English anchor existed.

---

### Files Read
* [UrduSubtitleEngine.kt:1](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1) (lines `1–3858`, full file)
* [UrduSubtitleTranslator.kt:1](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/UrduSubtitleTranslator.kt:1) (lines `1–842`, full file)
* [SubtitleSyncAndManifest.kt:1](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt:1) (lines `1–661`, full file)
* [FixedNextRenderersFactory.kt:1](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/FixedNextRenderersFactory.kt:1) (lines `1–28`, full file)
* [CS3IPlayer.kt:140](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:140) (lines `140–250`, `880–915`, `1720–1940`)
* [CustomSubtitleDecoderFactory.kt:1](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt:1) (lines `1–140`, `310–435`)
* [FullScreenPlayer.kt:100](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/FullScreenPlayer.kt:100) (lines `100–140`)
* [RepoLinkGenerator.kt:670](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/RepoLinkGenerator.kt:670) (lines `670–755`)
* [GeneratorPlayer.kt:1835](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt:1835) (lines `1835–1900`)
* [DownloadManager.kt:10325](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt:10325) (lines `10325–10400`, `10645–10710`)
* [libs.versions.toml:34](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/gradle/libs.versions.toml:34) & [build.gradle.kts:400](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/build.gradle.kts:400)

---

### Boundaries
* **Extension (`cloudstream-extensions-phisher`) vs. Host (`cloudstream`) Classloader Boundary**:
  * Extensions run in a plugin `PathClassLoader` without access to ExoPlayer's live `AudioSink`, `FixedNextRenderersFactory`, or downloaded local MP4 files. Therefore:
    * **Extension (`UrduSubtitleEngine`)** owns: catalogue harvesting, raw-orthography language verification, `UrduMtDumpDetector` filtering, reference-sidecar `alass` alignment (aligning harvested human Urdu `.srt` against concurrent stream-provider English `.vtt` sidecars), and extension-side Bol-Chal localization + `CPS <= 17` refinement.
    * **Host (`cloudstream`)** owns: live `ForwardingAudioSink` PCM VAD + `DynamicSyncedSubtitle` inside `CS3IPlayer`, offline/seekable multi-window `MediaExtractor`+`MediaCodec` audio VAD inside `SubtitleSyncEngine`/`DownloadManager`, and host-side `UrduSubtitleTranslator` parity (which currently lacks `UrduSubtitleEngine`'s bol-chal idiom/calque rules when called from `RepoLinkGenerator.kt:727`, `GeneratorPlayer.kt:1859`, and `DownloadManager.kt:10677`).
* **Media3 `1.9.3` + `nextlib-media3ext 1.9.3-0.12.0` Audio Threading Boundary**:
  * `AudioSink.handleBuffer` executes on ExoPlayer's real-time playback thread. The PCM tap must ONLY copy/decimate into a pre-allocated lock-free ring buffer (`< 5 µs`), running FFT/VAD/`alass` strictly on `Dispatchers.Default`, and resetting ring state on `flush()` / `reset()` (seeks & track switches).
* **SubtitleCat CDN Boundary**:
  * Note an existing cross-repo divergence: [UrduSubtitleEngine.kt:2045](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2045) explicitly forbids uploading machine-translated `.srt`s to `subtitlecat.com/upload_subtitles.php` so MT files never pollute public human indexes, whereas [FallbackSubtitleHarvester.kt:3182](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/FallbackSubtitleHarvester.kt:3182) still calls `UrduSubtitleTranslator.publishToSubtitleCatCdn`.

---

### Non-Obvious Things
1. **Android TV & HW-Only Mode Bypass `FixedNextRenderersFactory` Completely**:
   * In [CS3IPlayer.kt:1778-1796](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:1778), when `profile.useExtensionRenderers == false` (which is the **default on all Android TV devices** and whenever HW-only decoding is selected!), `CS3IPlayer` instantiates raw `DefaultRenderersFactory(context)` instead of `FixedNextRenderersFactory(context)`. Wiring a PCM tap only inside `FixedNextRenderersFactory` will silently leave 100% of Android TV users without live audio sync unless both branches share the custom `buildAudioSink` factory.
2. **`CS3IPlayer.setSubtitleOffset` Triggers `seekTime(-1)` (Audio/Video Stall + Flicker)**:
   * [CS3IPlayer.kt:882-885](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:882) sets `CustomDecoder.subtitleOffset` and immediately calls `seekTime(-1)` because [CustomSubtitleDecoderFactory.kt:346](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt:346) bakes `subtitleOffset` into `CuesWithTiming` only once at `parse()` time. Calling `setSubtitleOffset` from a live VAD loop will repeatedly flush ExoPlayer's decoders and stutter playback! Wrapping the `Subtitle` returned by `DelegatingSubtitleDecoder.decode` ([CustomSubtitleDecoderFactory.kt:429](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt:429)) in a dynamic piecewise time-warping `Subtitle` eliminates `seekTime(-1)` completely — **provided** events with old `eventTime <= positionUs` are never shifted forward past `positionUs` (which would violate Media3 `TextRenderer`'s monotonic `nextSubtitleEventIndex` cursor).
3. **`ForwardingAudioSink.handleBuffer` Beats `BaseAudioProcessor.queueInput` — With Two Crucial Caveats**:
   * `BaseAudioProcessor.queueInput(ByteBuffer)` strips `presentationTimeUs` (drifting across `SonicAudioProcessor` speed changes and HLS discontinuities), whereas `ForwardingAudioSink.handleBuffer(buffer, presentationTimeUs, count)` receives exact microsecond PTS from both `MediaCodecAudioRenderer` and `nextlib`'s `FfmpegAudioRenderer`.
   * *Caveat 1*: ExoPlayer calls `handleBuffer` repeatedly with the **same** buffer while `AudioTrack` is full — sample PCM **only** after `super.handleBuffer(...)` returns `true` (recording `buffer.position()` beforehand!).
   * *Caveat 2*: `AudioTrack`'s PCM buffer (`DefaultAudioTrackBufferSizeProvider.maxPcmBufferDurationUs`) is only **`250–750 ms`** ahead of speakers (compressed `LoadControl` buffers live upstream in `SampleQueue`), so pure `AudioSink` lookahead is `~0.5 s` — making background sparse `MediaExtractor` window probing essential when a subtitle is shifted *early* (`-10s`) and needs pre-correction before minute 1.
4. **Slicing Merged Urdu SOV Sentences by English SVO Character Length Spoils Plot Twists**:
   * [UrduSubtitleEngine.kt:1677-1720](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:1677) merges English SVO continuation cues (`"The traitor in your unit is..."` | `"...Captain Miller."`) into Urdu SOV (`"تمہاری یونٹ کا غدار کیپٹن ملر ہے۔"`), moving the reveal object *before* the verb into Cue 1 — displaying `"کیپٹن ملر"` seconds before the actor speaks it.
5. **Acoustic Speech Onsets Are Sharp (`±20 ms`), Hangover Ends Are Biased (`+150 ms`)**:
   * `alass` overlap scoring finds the right segment and rough offset, but final sub-100ms lip-sync polish must align `cueStartMs` to `vadOnsetMs - 35ms` (onset residuals only), never midpoint or `vadEndMs`, because VAD hangover stretches every span tail.

---

### Open Questions
1. **Optional LLM Configuration Surface**: Should user-supplied LLM credentials (`endpoint`, `apiKey`, `model` — e.g., Gemini Flash / Groq / OpenRouter / Ollama) live in CloudStream's global Subtitle Settings synced to extensions via `cloudstream_subtitle_mode_prefs` (mirroring `subtitle_mode` in [UrduSubtitleEngine.kt:285](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:285)), so the user configures it once for both streaming and offline downloads?
2. **Stiff / Post-Edited MT Track Policy (`mtDumpScore in 30..59`)**: When a catalogue track from SubDL/OpenSubtitles is clean Urdu script without broken SMT, but scores as literal NMT (`mtDumpScore >= 60`), should it be hard-dropped so `Urdu [Neural AI]` (synced to the exact video provider's English sidecar + bol-chal adapted) takes priority, or retained as a lower-ranked fallback track when no English donor exists?
3. **SubtitleCat Public Upload Gate**: Should [FallbackSubtitleHarvester.kt:3182](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/FallbackSubtitleHarvester.kt:3182) stop calling `UrduSubtitleTranslator.publishToSubtitleCatCdn` to match [UrduSubtitleEngine.kt:2045](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt:2045)'s invariant against polluting public catalogues with machine-translated `-ur.srt` dumps?