package com.phisher98

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * StreamPlayShield — the extension's production policy engine for ad and tracker traffic.
 *
 * Design philosophy: an unfiltered, neutral DNS race (Cloudflare/Google/Quad9/AdGuard, see
 * [OptimizedDns]) delivers what the internet really thinks a domain resolves to, while THIS
 * engine alone decides what the app is allowed to talk to. Deterministic local policy +
 * neutral resolution is the architecture serious DNS-level blockers converge on; delegating
 * the decision to a "filtered" upstream resolver would be neither predictable nor auditable
 * (every operator ships an opaque, ad-hoc list and can silently blackhole streaming CDNs —
 * the exact failure mode the 4-operator race was built to avoid).
 *
 * Three enforcement tiers, four enforcement points, one registry brain:
 *
 *  T1 — DNS gate ([OptimizedDns.lookup]): a blocked hostname never produces a resolver
 *       round-trip at all. Nothing is sent to DoH or system DNS for it, and callers observe
 *       the standard [java.net.UnknownHostException] contract (an IOException) that every
 *       error wrapper in this codebase already treats as "network failed, move on".
 *
 *  T2a — OkHttp application interceptor ([ShieldRequestInterceptor], installed on the shared
 *        `app.baseClient` by [NetworkOptimizer]): because CloudStream shares one OkHttpClient
 *        process-wide, this tier protects not only StreamPlay but the other extensions'
 *        traffic as well — the whole app gets a clean network stack. It (a) hard-blocks
 *        egress to known ad/tracker infrastructure, (b) strips tracking particles (utm_*,
 *        fbclid, gclid, ...) from the request URL before anything hits the wire, (c)
 *        applies an origin-only cross-host Referer policy so full page URLs (often carrying
 *        sku/episode ids) never leak to third parties, and (d) verifies the FINAL response
 *        of a chain — a hop that lands on blocked infrastructure (a cached redirect target,
 *        for example) is refused at delivery, so a policy change can never be outlived by a
 *        stale cache entry. Anti-hotlink pins for our own streaming CDNs pass through
 *        byte-for-byte: referrer relationships the extractors deliberately establish are
 *        excluded from the policy via the vetted allowlist.
 *
 *  T2b — OkHttp network hop guard ([ShieldHopGuardInterceptor], also installed on
 *        `app.baseClient`): application interceptors only ever see the original request,
 *        while network interceptors observe every redirect hop — so a 30x into known
 *        ad/tracker infrastructure is vetoed at the hop boundary, before the HTTP exchange,
 *        regardless of how the chain was reached (fresh redirect, retry, or a pooled
 *        connection that outlived a registry update). Block-only by design: request
 *        rewriting stays in the single T2a contract, one owner per concern.
 *
 *  T3 — Emission hygiene ([StreamLinkOptimizer.optimize] + [StreamLinkOptimizer.StreamDeduplicator]):
 *       every playback link a source produces is scrubbed of tracking particles before it
 *       is offered to the player, and any resolved link that still points at blocked ad or
 *       tracker infrastructure is dropped at the dedup gate with a metric. The player is
 *       therefore never handed a URL this engine would have vetoed on the wire.
 *
 * Registry design:
 *  - Curated registrable suffix-domains only ([BLOCKED_AD_DOMAINS], [BLOCKED_TRACKER_DOMAINS]) —
 *    the same exact-and-parent granularity as the AdGuard SDNS filter. No structural
 *    wildcards: a label merely containing "ads" must never decide a block, that is how
 *    over-blocking slips in and silently breaks playback.
 *  - Entries are full registrable domains, so matching is a plain right-to-left suffix walk
 *    over the host labels (multi-label TLDs like "gemius.co.uk" are simply registered as-is).
 *
 * Performance:
 *  - Host classification is bounded char scanning plus at most (labels) hash lookups — no
 *    regex and no URL parser on the decision path. The interceptor reuses OkHttp's
 *    already-parsed HttpUrl, costing only a few hundred nanoseconds per request.
 *  - [stripTrackingParticles] returns the original reference on the zero-particle fast path
 *    and rebuilds only the query otherwise; fragments are preserved verbatim (manifest-URL
 *    variants such as `#VID=` rely on them) and kept params keep their raw encoding.
 *
 * Observability: [metrics] exposes per-tier counters and [describe] a summary line, so a
 * debug log answers "how much junk did the shield keep out" at a glance.
 */
object StreamPlayShield {

    private const val TAG = "StreamPlayShield"

    // =========================================================================
    // 1. The curated registry (registrable domains, lowercase, no wildcards)
    // =========================================================================

    /**
     * Ad serving, ad exchange and ad-verification infrastructure.
     *
     * Provenance: the core of the AdGuard DNS default filter and the blocklistproject ads
     * list, the DDG Tracker Radar top-100 ad domains (2025/2026 editions), plus the
     * networks that specifically monetise streaming portals (ExoClick, Adsterra,
     * TrafficJunky, PropellerAds, MGID families).
     */
    internal val BLOCKED_AD_DOMAINS: Set<String> = setOf(
        // Google ad stack
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adservice.google.com",
        "ads.google.com",
        "admob.com",
        // Amazon ads
        "amazon-adsystem.com",
        // Open exchanges / SSPs / DSPs / RTB
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "openxcdn.net",
        "casalemedia.com",
        "adnxs.com",
        "adform.net",
        "adformdsp.net",
        "adsrvr.org",
        "bidswitch.net",
        "advertising.com",
        "smartadserver.com",
        "smartclip.net",
        "am15.net",
        "yieldmo.com",
        "sharethrough.com",
        "indexww.com",
        "media.net",
        "criteo.com",
        "criteo.net",
        "bidr.io",
        "yieldlab.net",
        "servesharp.net",
        // Ad verification / fraud measurement
        "moatads.com",
        "adsafeprotected.com",
        "doubleverify.com",
        // Content discovery / native ads
        "taboola.com",
        "outbrain.com",
        "revcontent.com",
        "mgid.com",
        // The networks that specifically litter streaming portals
        "exoclick.com",
        "exdynsrv.com",
        "exosrv.com",
        "tsyndicate.com",
        "clickaine.com",
        "clickadu.com",
        "juicyads.com",
        "ero-advertising.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "onclickads.net",
        "trafficjunky.net",
        "trafficfactory.biz",
        "adcash.com",
        "adsterra.com",
        "zqtk.net",
        "4dsply.com",
        // Open exchanges / header bidding / video ad servers (round two)
        "appnexus.com",
        "improvedigital.com",
        "emxdgt.com",
        "sonobi.com",
        "gumgum.com",
        "sovrn.com",
        "lijit.com",
        "triplelift.com",
        "33across.com",
        "loopme.me",
        "smaato.net",
        "admixer.net",
        "adtelligent.com",
        // Video ad serving and player-side monetisation (runs inside streaming players)
        "spotxchange.com",
        "springserve.com",
        "teads.tv",
        "unrulymedia.com",
        "connatix.com",
        "primis.tech",
        "vidoomy.com",
        "aniview.com",
        "beachfront.com",
        "tremorhub.com",
        "cpmstar.com",
        "exponential.com",
        "tribalfusion.com",
        // Popunder / redirect monetisers that litter streaming mirrors
        "adnium.com",
        "ad-maven.com",
        "monetag.com",
        "hilltopads.net",
        "trafficstars.com",
        "zeropark.com",
        "adspyglass.com",
        "dtscout.com",
        // Publisher-side ad tech
        "ezoic.net",
        "ezoic.com",
        "adpushup.com",
        "adroll.com",
        "zemanta.com",
        // Streaming-portal redirect gateways (repo research: the AnimePahe front hijack lands here)
        "qyvir.com",
        // RU/CIS ad stack
        "adriver.ru"
    )

    /**
     * Session-behaviour analytics, pixels and identity beacons: these hosts receive
     * cross-site behavioural history, which has no legitimate role in stream resolution.
     *
     * Provenance: AdGuard DNS tracking-protection core, DDG Tracker Radar tracking top-100
     * and the Web Almanac 2025 privacy chapter's most prevalent trackers.
     */
    internal val BLOCKED_TRACKER_DOMAINS: Set<String> = setOf(
        // Google measurement
        "google-analytics.com",
        "googletagmanager.com",
        "app-measurement.com",
        "analytics.google.com",
        // Meta / X / Reddit / Pinterest / Snapchat / LinkedIn pixels
        "connect.facebook.net",
        "analytics.facebook.com",
        "pixel.facebook.com",
        "analytics.twitter.com",
        "static.ads-twitter.com",
        "analytics.pinterest.com",
        "events.pinterest.com",
        "tr.snapchat.com",
        "ads.linkedin.com",
        // Site analytics
        "scorecardresearch.com",
        "quantserve.com",
        "chartbeat.com",
        "mixpanel.com",
        "amplitude.com",
        "kissmetrics.com",
        "heapanalytics.com",
        "crazyegg.com",
        "hotjar.com",
        "clarity.ms",
        "statcounter.com",
        "histats.com",
        "mc.yandex.ru",
        "bat.bing.com",
        "gemius.pl",
        // Consent beacon infrastructure (IAB TCF consent-engine endpoints)
        "consensu.org",
        // Adobe Experience Cloud measurement and identity
        "demdex.net",
        "omtrdc.net",
        "2o7.net",
        "everesttech.net",
        "adobedc.net",
        // Data clouds / DMPs / identity resolvers
        "bluekai.com",
        "bkrtx.com",
        "addthis.com",
        "agkn.com",
        "krxd.net",
        "exelator.com",
        "imrworldwide.com",
        "crwdcntrl.net",
        "mathtag.com",
        "turn.com",
        "tapad.com",
        "sitescout.com",
        "rfihub.com",
        "owneriq.net",
        "simpli.fi",
        "steelhousemedia.com",
        "id5-sync.com",
        "liadm.com",
        "rlcdn.com",
        "pippio.com",
        "permutive.com",
        // Session replay / product analytics
        "fullstory.com",
        "mouseflow.com",
        "luckyorange.com",
        "smartlook.com",
        "inspectlet.com",
        "logrocket.com",
        "logrocket.io",
        "quantummetric.com",
        // Customer data platforms
        "segment.com",
        "segment.io",
        "mparticle.com",
        // Social beacon subdomains (the parent registrants must never be blocked)
        "analytics.tiktok.com",
        "ct.pinterest.com",
        "alb.reddit.com",
        // RU/CIS counters and audience panels
        "top-fwz1.mail.ru",
        "counter.yadro.ru",
        "liveinternet.ru",
        "mediascope.net",
        "gemius.lt",
        // Heatmap asset host
        "hotjar.io"
    )

    /**
     * The extension's own streaming faces. Any host dot-suffixing to one of these is always
     * allowed, regardless of registry contents — the guarantee that the block list can grow
     * without ever colliding with the active source fleet (extractor-pinned anti-hotlink
     * referers to these domains are likewise exempt from the Referer policy).
     */
    internal val VETTED_SOURCE_DOMAINS: Set<String> = setOf(
        "vidlink.pro",
        "vidsrc.pm",
        "vidfast.vc",
        "moon.zenoak.top",
        "vidsrc.su",
        "themoviedb.vidsrc.su",
        "dragonballzfans.xyz",
        "vidrock.net",
        "galacticvoyagers.xyz",
        "moonfirekeeper.lol",
        "meowtv.ru",
        "hscow.com",
        "fcxmb.com",
        "vidjoy.org",
        "krico443jez.com",
        "slast430did.com",
        "animex.one",
        "aniwatchtv.site",
        "cheaptruckrepairs.cc",
        "netoda.tech",
        "hakunaymatata.com",
        "kwik.cx",
        "vix-content.net"
    )

    /** Category bit flags recorded into the merged suffix index. */
    const val CATEGORY_AD = 1
    const val CATEGORY_TRACKER = 2
    const val CATEGORY_ALLOW = 0

    /** Merged suffix index: registrable domain -> category bit flags. */
    private val SUFFIX_INDEX: Map<String, Int> = run {
        val index = HashMap<String, Int>(BLOCKED_AD_DOMAINS.size + BLOCKED_TRACKER_DOMAINS.size)
        fun register(domains: Collection<String>, flag: Int) {
            for (domain in domains) {
                val normalized = domain.lowercase(Locale.ROOT).trim()
                if (normalized.isNotEmpty()) index[normalized] = (index[normalized] ?: 0) or flag
            }
        }
        register(BLOCKED_AD_DOMAINS, CATEGORY_AD)
        register(BLOCKED_TRACKER_DOMAINS, CATEGORY_TRACKER)
        index
    }

    // =========================================================================
    // 2. Host decision logic (pure, allocation-bounded)
    // =========================================================================

    /**
     * The single decision function every tier routes through.
     *
     * [host] is a bare hostname or dotted suffix chain (e.g. "stats.g.doubleclick.net").
     * Walks every suffix starting after each dot, right-to-left through the label chain,
     * and returns the matched category bit-combo, or [CATEGORY_ALLOW] for an unrestricted
     * host.
     *
     * Guarantees (unit-tested):
     *  - every registry entry and every subdomain of it is matched;
     *  - sibling lookalikes are NOT matched ("interdomain.doubleclick.example.com" stays
     *    allowed because the walk never crosses a dot it does not own);
     *  - vetted source domains and their subdomains are always released, even if a future
     *    list edit names them;
     *  - IP literals and blank input always release.
     */
    fun classifyHost(host: String): Int {
        if (host.isEmpty()) return CATEGORY_ALLOW
        val lowered = host.lowercase(Locale.ROOT)
        if (isIpLiteral(lowered)) return CATEGORY_ALLOW
        for (vetted in VETTED_SOURCE_DOMAINS) {
            if (isDotSuffix(lowered, vetted)) return CATEGORY_ALLOW
        }

        var category = suffixIndexAt(lowered, 0)
        if (category == (CATEGORY_AD or CATEGORY_TRACKER)) return category
        var dot = lowered.indexOf('.', 0)
        while (dot != -1) {
            val suffixStart = dot + 1
            if (suffixStart >= lowered.length) break
            category = category or suffixIndexAt(lowered, suffixStart)
            if (category == (CATEGORY_AD or CATEGORY_TRACKER)) return category
            dot = lowered.indexOf('.', dot + 1)
        }
        return category
    }

    private fun suffixIndexAt(loweredHost: String, start: Int): Int {
        val candidate = if (start == 0) loweredHost else loweredHost.substring(start)
        return SUFFIX_INDEX[candidate] ?: CATEGORY_ALLOW
    }

    /** True when [candidate] equals [domain] or is one of its subdomains (dot-prefixed). */
    fun isDotSuffix(candidate: String, domain: String): Boolean {
        if (candidate == domain) return true
        if (candidate.length <= domain.length) return false
        if (!candidate.endsWith(domain)) return false
        return candidate[candidate.length - domain.length - 1] == '.'
    }

    /**
     * True for IPv4 dotted-quad literals and bracketed IPv6 literals; hostnames (including
     * alpha-containing lookalikes) always return false. This is strictly an optimisation:
     * a numeric "host" would simply miss the registry anyway.
     */
    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.startsWith("[")) return true
        if (host[0] !in '0'..'9') return false
        var dots = 0
        for (i in host.indices) {
            val c = host[i]
            if (c == '.') {
                dots++
            } else if (c !in '0'..'9') {
                return false
            }
        }
        return dots == 3
    }

    fun isBlockedHost(host: String): Boolean = classifyHost(host) != CATEGORY_ALLOW

    fun isBlockedAdHost(host: String): Boolean = (classifyHost(host) and CATEGORY_AD) != 0

    fun isBlockedTrackerHost(host: String): Boolean = (classifyHost(host) and CATEGORY_TRACKER) != 0

    /**
     * Bounded, regex-free extraction of the hostname segment of a URL-ish string.
     *
     * Handles absolute URLs with userinfo, default- and explicit-port forms, scheme-less
     * "host/path" residue from scraping, and bare hosts. Returns "" for empty, scheme-only
     * or path-only inputs; empty hosts classify to an allow decision instead of crashing.
     */
    fun hostOf(urlish: String): String {
        if (urlish.isEmpty()) return ""

        var start = 0
        val schemeEnd = urlish.indexOf("://")
        if (schemeEnd == 0) return ""
        if (schemeEnd > 0) start = schemeEnd + 3

        // Skip userinfo ("scheme://user:pass@host/..."), if present.
        val firstSlash = indexOfAnyOf(urlish, start, '/', '?')
        val userEnd = urlish.indexOf('@', start)
        if (userEnd != -1 && (firstSlash == -1 || userEnd < firstSlash)) {
            start = userEnd + 1
        }

        var end = indexOfAnyOf(urlish, start, '/', '?', '#', ':')
        if (end == -1) end = urlish.length
        return if (end <= start) "" else urlish.substring(start, end).lowercase(Locale.ROOT)
    }

    private fun indexOfAnyOf(source: String, fromIndex: Int, vararg stopChars: Char): Int {
        for (i in fromIndex until source.length) {
            val c = source[i]
            for (stop in stopChars) {
                if (c == stop) return i
            }
        }
        return -1
    }

    fun isBlockedUrl(urlish: String): Boolean = classifyHost(hostOf(urlish)) != CATEGORY_ALLOW

    // =========================================================================
    // 3. Tracking-particle hygiene
    // =========================================================================

    /** Exact-name tracking particles proven to be pure attribution beacons. */
    internal val TRACKING_PARTICLE_PARAMS: Set<String> = setOf(
        // Google Ads / DoubleClick click ids
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source", "srsltid",
        // Microsoft / Yahoo / DuckDuckGo / Taboola / TikTok click ids
        "msclkid", "yclid", "twclid", "ttclid",
        // Meta / Facebook attribution
        "fbclid", "li_fat_id",
        // MailChimp, HubSpot, Vero, Adobe SAINT attribution
        "mc_eid", "mc_cid", "_hsenc", "_hsmi", "vero_conv", "vero_id", "s_kwcid",
        // Google Analytics cross-domain linker + legacy campaign fabric
        "_gl", "_ga", "_openstat",
        // Matomo / Piwik campaign fabric
        "pk_campaign", "pk_kwd", "pk_source", "pk_medium",
        // MailChimp campaign carriers
        "mkt_tok", "wickedid",
        // Adobe / HubSpot legacy carriers
        "s_cid", "hsctatracking",
        // Instagram / TikTok share attribution
        "igshid",
        // Alibaba page-relationship beacons
        "spm"
    )

    /** Name prefixes that sweep broad tracker parameter families generatively. */
    internal val TRACKING_PARTICLE_PREFIXES: Set<String> = setOf(
        "utm_",        // UTM attribution family (utm_source/medium/campaign/term/content/id/...)
        "hsa_",        // HubSpot ads family (hsa_cam/acc/ad/grp/net/...)
        "oly_anon_id", // Omeda identity carriers
        "oly_enc_id",
        "_gac_",       // Google Ads conversion linker family (_gac_UA-1234)
        "trk_",        // MailChimp campaign fabric (trk_contact, trk_msg, trk_module, ...)
        "mtm_"         // Matomo measurement family (mtm_campaign, mtm_source, ...)
    )

    fun isTrackingParticle(paramName: String): Boolean {
        val lowered = paramName.lowercase(Locale.ROOT)
        if (TRACKING_PARTICLE_PARAMS.contains(lowered)) return true
        for (prefix in TRACKING_PARTICLE_PREFIXES) {
            if (lowered.startsWith(prefix)) return true
        }
        return false
    }

    /**
     * Strips tracking particles from [urlish]'s query section without touching functional
     * parameters, the fragment, or parameter encodings.
     *
     * Returns the ORIGINAL string reference when nothing needs stripping (the
     * zero-allocation fast path matters because this runs per emitted/requested URL);
     * otherwise rebuilds only the query and re-attaches the fragment byte-for-byte, so
     * manifest-URL fragment routing (e.g. `#VID=` variants) survives scrubbing.
     */
    fun stripTrackingParticles(urlish: String): String {
        if (urlish.isEmpty()) return urlish
        val queryStart = urlish.indexOf('?')
        if (queryStart == -1) return urlish
        val fragmentStart = urlish.indexOf('#', queryStart + 1)

        val queryEnd = if (fragmentStart == -1) urlish.length else fragmentStart
        val query = urlish.substring(queryStart + 1, queryEnd)
        if (query.isEmpty()) return urlish

        val fragment = if (fragmentStart == -1) "" else urlish.substring(fragmentStart)
        val prefix = urlish.substring(0, queryStart)

        val kept = StringBuilder(query.length)
        var particleFound = false
        var index = 0
        while (index < query.length) {
            val ampersand = query.indexOf('&', index)
            val pairEnd = if (ampersand == -1) query.length else ampersand
            if (pairEnd > index) {
                val pair = query.substring(index, pairEnd)
                val equals = pair.indexOf('=')
                val nameOnly = if (equals == -1) pair else pair.substring(0, equals)
                if (nameOnly.isNotEmpty() && isTrackingParticle(nameOnly)) {
                    particleFound = true
                } else {
                    if (kept.isNotEmpty()) kept.append('&')
                    kept.append(pair)
                }
            }
            index = pairEnd + 1
        }

        if (!particleFound) return urlish
        val cleanedQuery = kept.toString()
        return prefix + (if (cleanedQuery.isEmpty()) "" else "?$cleanedQuery") + fragment
    }

    fun hasTrackingParticles(urlish: String): Boolean = stripTrackingParticles(urlish) !== urlish

    // =========================================================================
    // 4. Tier-2 interceptors (installed on the shared app.baseClient)
    // =========================================================================

    /**
     * Thrown (deliberately an IOException) when the shield refuses a request: at the T2a
     * preflight, at T2a final-response verification, or at a T2b network hop.
     */
    class AdBlockedException(val host: String, val category: String) : IOException(
        "StreamPlayShield blocked request to ad/tracker host '$host' ($category)"
    )

    /**
     * Pure Tier-2 decision function: evaluates a [Request] and returns either
     * [ShieldDecision.Blocked] (egress refused) or [ShieldDecision.Allowed] carrying an
     * optional rewritten request (particle strip / referer policy).
     *
     * Extracting the decision from the [Interceptor] makes the full contract testable with
     * plain [Request.Builder] objects — no chain or socket scaffolding — and keeps the
     * interceptor itself a thin throw-or-proceed shell.
     *
     * Contract (unit-tested):
     *  1. blocked hosts -> [ShieldDecision.Blocked] with the merged category + metric;
     *  2. intact URLs on allowed hosts pass with `rewritten == null` (zero-copy fast path);
     *  3. particle-bearing URLs are rebuilt via OkHttp's own builder (encodings preserved);
     *  4. ONLY cross-host, non-vetted `Referer` headers are rewritten to origin form;
     *     vetted/pinned anti-hotlink referers (vidrock/CDN family) and same-host referers
     *     stay byte-for-byte identical.
     */
    fun evaluateRequest(request: Request): ShieldDecision {
        val url = request.url
        val category = classifyHost(url.host)
        if (category != CATEGORY_ALLOW) {
            countBlockedRequest(category)
            return ShieldDecision.Blocked(url.host, category)
        }

        var rewritten: Request? = null

        // Particle strip (OkHttp round-trip keeps every kept param's raw encoding).
        val particleNames = url.queryParameterNames.filter { isTrackingParticle(it) }
        if (particleNames.isNotEmpty()) {
            val urlBuilder = url.newBuilder()
            for (name in particleNames) urlBuilder.removeAllQueryParameters(name)
            rewritten = request.newBuilder().url(urlBuilder.build()).build()
            countParticleScrubbed()
        }

        // Origin-only cross-host Referer policy (pinned source referers exempt).
        val referer = request.header("Referer")
        if (referer != null) {
            val refHost = hostOf(referer)
            if (refHost.isNotEmpty() && refHost != url.host && !isVettedRefererHost(refHost)) {
                val refOrigin = originOf(referer)
                if (refOrigin != null) {
                    rewritten = (rewritten ?: request).newBuilder()
                        .header("Referer", refOrigin)
                        .build()
                    countRefererLeaned()
                }
            }
        }

        return ShieldDecision.Allowed(rewritten)
    }

    /** The sealed result of [evaluateRequest]. */
    sealed interface ShieldDecision {
        /** Egress refused: [category] is the merged [CATEGORY_AD]/[CATEGORY_TRACKER] bit-combo. */
        data class Blocked(val host: String, val category: Int) : ShieldDecision

        /** Host released to the network; `rewritten` is null when nothing needed changing. */
        data class Allowed(val rewritten: Request?) : ShieldDecision
    }

    /**
     * OkHttp application interceptor: the Tier-2a enforcement point. A thin shell around
     * the pure [evaluateRequest] decision (see its KDoc for the full contract) plus the
     * final-response verification — whatever the redirect chain, the HTTP cache or the
     * connection pool served, a chain that finalises on blocked infrastructure is closed
     * and refused before the caller can ever see the body. This is what makes a registry
     * update impossible to outlive for responses that never re-ran the preflight (a hop
     * served from OkHttp's cache, for example).
     */
    class ShieldRequestInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            when (val decision = evaluateRequest(request)) {
                is ShieldDecision.Blocked ->
                    throw AdBlockedException(decision.host, categoryLabel(decision.category))

                is ShieldDecision.Allowed -> {
                    val response = chain.proceed(decision.rewritten ?: request)
                    val finalHost = response.request.url.host
                    val finalCategory = classifyHost(finalHost)
                    if (finalCategory == CATEGORY_ALLOW) return response
                    response.close()
                    countBlockedRequest(finalCategory)
                    countBlockedHop()
                    throw AdBlockedException(finalHost, categoryLabel(finalCategory))
                }
            }
        }
    }

    private fun categoryLabel(category: Int): String {
        val hasAd = (category and CATEGORY_AD) != 0
        val hasTracker = (category and CATEGORY_TRACKER) != 0
        return when {
            hasAd && hasTracker -> "ad+tracker"
            hasAd -> "ad"
            hasTracker -> "tracker"
            else -> "unknown"
        }
    }

    fun newRequestInterceptor(): Interceptor = ShieldRequestInterceptor()

    /**
     * OkHttp network interceptor: the Tier-2b hop guard. Network interceptors observe every
     * redirect hop (application interceptors only ever see the original request), so this
     * closes the redirect-chain hole deterministically: a 30x into known ad/tracker
     * infrastructure is vetoed at the hop boundary regardless of how the chain was reached —
     * fresh redirect, retry, or a pooled connection that outlived a registry update.
     *
     * Deliberately block-only: request rewriting (particle strip / referer policy) stays in
     * the single T2a contract — one owner per concern.
     */
    class ShieldHopGuardInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val category = classifyHost(request.url.host)
            if (category != CATEGORY_ALLOW) {
                countBlockedRequest(category)
                countBlockedHop()
                throw AdBlockedException(request.url.host, categoryLabel(category))
            }
            return chain.proceed(request)
        }
    }

    fun newHopGuardInterceptor(): Interceptor = ShieldHopGuardInterceptor()

    /** "scheme://host[:port]" of a header-style URL string, or null when not absolute. */
    fun originOf(urlish: String): String? {
        val schemeEnd = urlish.indexOf("://")
        if (schemeEnd <= 0) return null
        var authorityStart = schemeEnd + 3
        val firstSlash = indexOfAnyOf(urlish, authorityStart, '/')
        val userEnd = urlish.indexOf('@', authorityStart)
        if (userEnd != -1 && (firstSlash == -1 || userEnd < firstSlash)) authorityStart = userEnd + 1

        var end = indexOfAnyOf(urlish, authorityStart, '/', '?', '#')
        if (end == -1) end = urlish.length
        if (end <= authorityStart) return null
        return urlish.substring(0, end)
    }

    private fun isVettedRefererHost(host: String): Boolean {
        for (vetted in VETTED_SOURCE_DOMAINS) {
            if (isDotSuffix(host, vetted)) return true
        }
        return false
    }

    // =========================================================================
    // 5. Metrics
    // =========================================================================

    private val blockedAdRequests = AtomicInteger(0)
    private val blockedTrackerRequests = AtomicInteger(0)
    private val blockedRedirectHops = AtomicInteger(0)
    private val dnsGateCalls = AtomicInteger(0)
    private val particleScrubs = AtomicInteger(0)
    private val refererLeaned = AtomicInteger(0)
    private val emissionsBlocked = AtomicInteger(0)

    internal fun countBlockedRequest(category: Int) {
        if ((category and CATEGORY_AD) != 0) blockedAdRequests.incrementAndGet()
        if ((category and CATEGORY_TRACKER) != 0) blockedTrackerRequests.incrementAndGet()
    }

    /** A block that happened past the request preflight: a redirect hop or a final response. */
    internal fun countBlockedHop() { blockedRedirectHops.incrementAndGet() }
    internal fun countDnsGate() { dnsGateCalls.incrementAndGet() }
    internal fun countParticleScrubbed() { particleScrubs.incrementAndGet() }
    internal fun countRefererLeaned() { refererLeaned.incrementAndGet() }
    internal fun countEmissionBlocked() { emissionsBlocked.incrementAndGet() }

    data class Metrics(
        val blockedAdRequests: Int,
        val blockedTrackerRequests: Int,
        val dnsGateCalls: Int,
        val particleScrubs: Int,
        val refererLeaned: Int,
        val emissionsBlocked: Int,
        val blockedRedirectHops: Int
    )

    fun metrics(): Metrics = Metrics(
        blockedAdRequests.get(),
        blockedTrackerRequests.get(),
        dnsGateCalls.get(),
        particleScrubs.get(),
        refererLeaned.get(),
        emissionsBlocked.get(),
        blockedRedirectHops.get()
    )

    /** One-line observability summary for debug logs. */
    fun describe(): String {
        val m = metrics()
        return "StreamPlayShield[int] blocked: ${m.blockedAdRequests}ad/${m.blockedTrackerRequests}trk " +
            "hop:${m.blockedRedirectHops} dns:${m.dnsGateCalls} scrub:${m.particleScrubs} " +
            "ref:${m.refererLeaned} emit:${m.emissionsBlocked}"
    }

    fun resetMetrics() {
        blockedAdRequests.set(0)
        blockedTrackerRequests.set(0)
        blockedRedirectHops.set(0)
        dnsGateCalls.set(0)
        particleScrubs.set(0)
        refererLeaned.set(0)
        emissionsBlocked.set(0)
    }
}
