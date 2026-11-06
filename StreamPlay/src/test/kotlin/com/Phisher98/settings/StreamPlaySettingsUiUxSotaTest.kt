package com.phisher98.settings

import com.phisher98.ProvidersFragment
import com.phisher98.StreamPlayConcurrency
import com.phisher98.StreamPlayDesignTokens
import com.phisher98.UrduSubtitleEngine
import com.phisher98.buildProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class StreamPlaySettingsUiUxSotaTest {

    private fun resolveModuleRoot(): File {
        val candidates = listOf(
            File("."),
            File("StreamPlay"),
            File("../StreamPlay")
        )
        return candidates.firstOrNull { File(it, "src/main/res/layout/fragment_main_settings.xml").exists() }
            ?: error("Could not locate StreamPlay module root from ${File(".").absolutePath}")
    }

    private fun srgbChannelToLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.2126 * srgbChannelToLinear(r) +
            0.7152 * srgbChannelToLinear(g) +
            0.0722 * srgbChannelToLinear(b)
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `wcag 2_2 AA contrast ratios hold across all obsidian surface and text tokens`() {
        val surfaces = listOf(
            "SURFACE_CANVAS" to StreamPlayDesignTokens.SURFACE_CANVAS,
            "SURFACE_ELEVATED" to StreamPlayDesignTokens.SURFACE_ELEVATED,
            "SURFACE_HIGH" to StreamPlayDesignTokens.SURFACE_HIGH
        )

        for ((name, surface) in surfaces) {
            val primaryRatio = contrastRatio(StreamPlayDesignTokens.TEXT_PRIMARY, surface)
            val secondaryRatio = contrastRatio(StreamPlayDesignTokens.TEXT_SECONDARY, surface)
            val mutedRatio = contrastRatio(StreamPlayDesignTokens.TEXT_MUTED, StreamPlayDesignTokens.SURFACE_TRACK)
            val softAccentRatio = contrastRatio(StreamPlayDesignTokens.ACCENT_SOFT, surface)
            val dangerRatio = contrastRatio(StreamPlayDesignTokens.STATUS_DANGER, surface)
            val focusRatio = contrastRatio(StreamPlayDesignTokens.BORDER_FOCUS, surface)

            assertTrue("Primary text contrast on $name ($primaryRatio) must be >= 11.0", primaryRatio >= 11.0)
            assertTrue("Secondary text contrast on $name ($secondaryRatio) must be >= 4.5", secondaryRatio >= 4.5)
            assertTrue("Muted text contrast on SURFACE_TRACK ($mutedRatio) must be >= 4.5", mutedRatio >= 4.5)
            assertTrue("Soft accent contrast on $name ($softAccentRatio) must be >= 7.0", softAccentRatio >= 7.0)
            assertTrue("Danger token contrast on $name ($dangerRatio) must be >= 4.5", dangerRatio >= 4.5)
            assertTrue("Focus ring contrast on $name ($focusRatio) must be >= 3.0", focusRatio >= 3.0)
        }

        val activeControlRatio = contrastRatio(StreamPlayDesignTokens.ON_ACCENT, StreamPlayDesignTokens.ACCENT_PRIMARY)
        assertTrue(
            "White label on ACCENT_PRIMARY ($activeControlRatio) must meet WCAG AA >= 4.5",
            activeControlRatio >= 4.5
        )
        assertEquals(
            "17.26",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.TEXT_PRIMARY, StreamPlayDesignTokens.SURFACE_CANVAS))
        )
        assertEquals(
            "6.67",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.TEXT_SECONDARY, StreamPlayDesignTokens.SURFACE_CARD))
        )
        assertEquals(
            "5.44",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.TEXT_MUTED, StreamPlayDesignTokens.SURFACE_CANVAS))
        )
        assertEquals(
            "5.56",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.TEXT_MUTED, StreamPlayDesignTokens.SURFACE_TRACK))
        )
        assertEquals(
            "5.17",
            String.format(Locale.US, "%.2f", activeControlRatio)
        )
        assertEquals(
            "9.48",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.ACCENT_SOFT, StreamPlayDesignTokens.SURFACE_CARD))
        )
        assertEquals(
            "8.29",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.DANGER_TEXT, StreamPlayDesignTokens.DANGER_SURFACE))
        )
        assertEquals(
            "13.87",
            String.format(Locale.US, "%.2f", contrastRatio(StreamPlayDesignTokens.BORDER_FOCUS, StreamPlayDesignTokens.SURFACE_CARD))
        )
    }

    @Test
    fun `nested corner radius hierarchy is strictly decreasing from dialog to segment`() {
        assertTrue(StreamPlayDesignTokens.RADIUS_DIALOG_DP > StreamPlayDesignTokens.RADIUS_SECTION_CARD_DP)
        assertTrue(StreamPlayDesignTokens.RADIUS_SECTION_CARD_DP > StreamPlayDesignTokens.RADIUS_ROW_DP)
        assertTrue(StreamPlayDesignTokens.RADIUS_ROW_DP > StreamPlayDesignTokens.RADIUS_SEGMENT_INNER_DP)
        assertEquals(48, StreamPlayDesignTokens.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun `bidi isolate prevents mixed-script Urdu parenthesis corruption`() {
        val isolatedUrdu = StreamPlayDesignTokens.isolateRtl("اردو")
        assertTrue(isolatedUrdu.startsWith("\u2067"))
        assertTrue(isolatedUrdu.endsWith("\u2069"))

        val label = "Urdu $isolatedUrdu"
        assertFalse("Segment label must not contain parentheses that invert under BiDi", label.contains("("))
        assertFalse("Segment label must not contain parentheses that invert under BiDi", label.contains(")"))
        assertFalse("Segment label must not contain flag emoji", label.contains("🇵🇰"))
        assertFalse("Segment label must not contain hype token SOTA", label.contains("SOTA"))
        assertFalse("SubtitleMode.URDU.displayLabel must not contain parentheses", UrduSubtitleEngine.SubtitleMode.URDU.displayLabel.contains("("))
    }

    @Test
    fun `zero hard-ban AI tells or off-token hexes exist across layouts drawables settings and providers`() {
        val root = resolveModuleRoot()
        val layoutDir = File(root, "src/main/res/layout")
        val drawableDir = File(root, "src/main/res/drawable")
        val settingsKotlinDir = File(root, "src/main/kotlin/com/Phisher98/settings")
        val mainKotlinDir = File(root, "src/main/kotlin/com/Phisher98")

        val layoutFiles = layoutDir.listFiles { f -> f.extension == "xml" }?.toList().orEmpty()
        val drawableFiles = drawableDir.listFiles { f -> f.extension == "xml" }?.toList().orEmpty()
        val settingsKotlinFiles = settingsKotlinDir.listFiles { f -> f.extension == "kt" }?.toList().orEmpty()
        val providerFiles = listOf(
            File(mainKotlinDir, "StreamPlay.kt"),
            File(mainKotlinDir, "StreamPlayAnime.kt"),
            File(mainKotlinDir, "StreamPlayStremioCatelog.kt"),
            File(mainKotlinDir, "StreamPlayConcurrency.kt"),
            File(mainKotlinDir, "UrduSubtitleEngine.kt")
        )

        assertEquals("Expected exactly 14 active layout XML files", 14, layoutFiles.size)
        assertEquals("Expected exactly 4 active drawable XML files", 4, drawableFiles.size)
        assertTrue("Expected settings Kotlin files to exist", settingsKotlinFiles.isNotEmpty())

        val bannedTokens = listOf(
            "🇬🇧",
            "🇵🇰",
            "✓",
            "✨",
            "ENGLISH DEFAULT",
            "URDU SOTA ACTIVE",
            "Subtitles: Urdu (اردو • SOTA)",
            "Subtitles: English (Default)",
            "Harvesters",
            "0ms switch",
            "Neural fallback",
            "JSON tiers",
            "Balanced (Recommended)",
            "Slow internet saver",
            "Max speed",
            "Dialog fields not found",
            "#6200EE",
            "#3700B3",
            "#D32F2F",
            "#B71C1C",
            "#4285F4",
            "#2E3440",
            "#4C566A",
            "#11151C",
            "#171C26",
            "#232B3A",
            "#64748B",
            "#F87171"
        )

        val allowedHexPalette = setOf(
            "#00000000",
            StreamPlayDesignTokens.HEX_SURFACE_CANVAS,
            StreamPlayDesignTokens.HEX_SURFACE_CARD,
            StreamPlayDesignTokens.HEX_SURFACE_CARD_PRESSED,
            StreamPlayDesignTokens.HEX_SURFACE_TRACK,
            StreamPlayDesignTokens.HEX_SURFACE_OVERLAY,
            StreamPlayDesignTokens.HEX_SURFACE_OVERLAY_PRESSED,
            StreamPlayDesignTokens.HEX_BORDER_SUBTLE,
            StreamPlayDesignTokens.HEX_BORDER_FOCUS,
            StreamPlayDesignTokens.HEX_TEXT_PRIMARY,
            StreamPlayDesignTokens.HEX_TEXT_SECONDARY,
            StreamPlayDesignTokens.HEX_TEXT_MUTED,
            StreamPlayDesignTokens.HEX_ACCENT_PRIMARY,
            StreamPlayDesignTokens.HEX_ACCENT_PRIMARY_PRESSED,
            StreamPlayDesignTokens.HEX_ACCENT_SOFT,
            StreamPlayDesignTokens.HEX_DANGER_SURFACE,
            StreamPlayDesignTokens.HEX_DANGER_SURFACE_PRESSED,
            StreamPlayDesignTokens.HEX_DANGER_BORDER,
            StreamPlayDesignTokens.HEX_DANGER_TEXT,
            "#FFFFFF"
        )
        val hexRegex = Regex("#[0-9A-Fa-f]{6,8}")

        for (file in layoutFiles + drawableFiles + settingsKotlinFiles + providerFiles) {
            val content = file.readText()
            for (banned in bannedTokens) {
                assertFalse(
                    "File ${file.name} contains hard-banned slop token '$banned'",
                    content.contains(banned)
                )
            }
            if (file in layoutFiles + settingsKotlinFiles) {
                assertFalse(
                    "File ${file.name} must not contain unspaced em-dash",
                    content.contains("—")
                )
            }
            if (file.extension == "xml") {
                assertFalse(
                    "XML ${file.name} must not use textAllCaps=\"true\"",
                    content.contains("android:textAllCaps=\"true\"")
                )
                assertFalse(
                    "XML ${file.name} must not hardcode layoutDirection=\"ltr\" (breaks RTL mirroring)",
                    content.contains("android:layoutDirection=\"ltr\"")
                )
                for (match in hexRegex.findAll(content)) {
                    val hex = match.value.uppercase()
                    assertTrue(
                        "XML ${file.name} contains off-token hex color '$hex' outside StreamPlayDesignTokens palette",
                        hex in allowedHexPalette
                    )
                }
            }
        }
    }

    @Test
    fun `main settings layout groups rows into 3 task sections with live value summaries and 48dp segment pills`() {
        val root = resolveModuleRoot()
        val mainLayoutFile = File(root, "src/main/res/layout/fragment_main_settings.xml")
        val content = mainLayoutFile.readText()

        assertTrue(content.contains("Subtitles &amp; language"))
        assertTrue(content.contains("Playback &amp; sources"))
        assertTrue(content.contains("Addons &amp; extensions"))
        assertFalse("Redundant subtitle_api_keys_btn must be removed from hero card", content.contains("subtitle_api_keys_btn"))
        assertFalse("Dead loginRow must be removed from main settings layout", content.contains("loginRow"))
        assertTrue("Initial D-pad focus must request subtitle_mode_pill_default", content.contains("<requestFocus"))

        val requiredValueSummaryIds = listOf(
            "languageValue",
            "wyzieValue",
            "providersValue",
            "performanceValue",
            "featureValue",
            "stremioStreamsValue",
            "stremioCatalogsValue"
        )
        for (id in requiredValueSummaryIds) {
            assertTrue("fragment_main_settings.xml must define live summary TextView '$id'", content.contains("@+id/$id"))
        }

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mainLayoutFile)
        val textViews = doc.getElementsByTagName("TextView")
        for (i in 0 until textViews.length) {
            val node = textViews.item(i)
            val id = node.attributes.getNamedItem("android:id")?.nodeValue.orEmpty()
            if (id.endsWith("subtitle_mode_pill_default") || id.endsWith("subtitle_mode_pill_urdu")) {
                val minHeight = node.attributes.getNamedItem("android:minHeight")?.nodeValue
                assertEquals("Segment pill $id must have 48dp minHeight", "48dp", minHeight)
            }
        }
    }

    @Test
    fun `every EditText across settings layouts has a preceding visible TextView label and 48dp touch height`() {
        val root = resolveModuleRoot()
        val layoutDir = File(root, "src/main/res/layout")
        val factory = DocumentBuilderFactory.newInstance()

        for (xmlFile in layoutDir.listFiles { f -> f.extension == "xml" }.orEmpty()) {
            val doc = factory.newDocumentBuilder().parse(xmlFile)
            val editTexts = doc.getElementsByTagName("EditText")
            for (i in 0 until editTexts.length) {
                val editNode = editTexts.item(i) as Element
                val editId = editNode.getAttribute("android:id")
                val minHeight = editNode.getAttribute("android:minHeight")
                val layoutHeight = editNode.getAttribute("android:layout_height")
                val has48dpHeight = minHeight == "48dp" || layoutHeight == "48dp"
                assertTrue(
                    "EditText $editId in ${xmlFile.name} must have at least 48dp height (found minHeight=$minHeight, layout_height=$layoutHeight)",
                    has48dpHeight
                )

                var prev: Node? = editNode.previousSibling
                while (prev != null && prev.nodeType != Node.ELEMENT_NODE) {
                    prev = prev.previousSibling
                }
                assertNotNull("EditText $editId in ${xmlFile.name} must have a preceding element sibling", prev)
                val prevElement = prev as Element
                assertEquals(
                    "EditText $editId in ${xmlFile.name} must be immediately preceded by a <TextView> label",
                    "TextView",
                    prevElement.tagName
                )
                assertTrue(
                    "Preceding TextView for $editId in ${xmlFile.name} must have non-blank android:text",
                    prevElement.getAttribute("android:text").isNotBlank()
                )
                assertTrue(
                    "Preceding TextView for $editId in ${xmlFile.name} must declare android:labelFor",
                    prevElement.getAttribute("android:labelFor").isNotBlank()
                )
            }
        }
    }

    @Test
    fun `concurrency tier vocabulary is unified across presets MainSettingsFragment and ConcurrencyBottomSheet`() {
        assertEquals("Stable", StreamPlayConcurrency.concurrencyTier(8))
        assertEquals("Balanced", StreamPlayConcurrency.concurrencyTier(16))
        assertEquals("Fast", StreamPlayConcurrency.concurrencyTier(32))
        assertEquals("Max", StreamPlayConcurrency.concurrencyTier(80))

        assertEquals("8 · Stable", StreamPlayDesignTokens.formatConcurrencySummary(8))
        assertEquals("16 · Balanced", StreamPlayDesignTokens.formatConcurrencySummary(16))
        assertEquals("32 · Fast", StreamPlayDesignTokens.formatConcurrencySummary(32))
        assertEquals("80 · Max", StreamPlayDesignTokens.formatConcurrencySummary(80))

        assertEquals("Stable", StreamPlayConcurrency.concurrencyLabel(8))
        assertEquals("Balanced", StreamPlayConcurrency.concurrencyLabel(16))
        assertEquals("Fast", StreamPlayConcurrency.concurrencyLabel(32))
        assertEquals("Max", StreamPlayConcurrency.concurrencyLabel(80))
    }

    @Test
    fun `subtitle source line lists active providers separated by middle dots and gates key-dependent sources`() {
        System.clearProperty("cloudstream.subsource.key")
        System.clearProperty("cloudstream.wyzie.key")

        assertEquals(5, StreamPlayDesignTokens.countReadyEnglishSources(null, null))
        assertEquals(6, StreamPlayDesignTokens.countReadyUrduSources(null, null))

        val enKeyless = StreamPlayDesignTokens.formatSubtitleSourcesLine(false, null, null)
        val urKeyless = StreamPlayDesignTokens.formatSubtitleSourcesLine(true, null, null)

        assertEquals("OpenSubtitles · SubDL · Subf2m · YTS · SubtitleCat", enKeyless)
        assertEquals("SubDL · Subf2m · YTS · OpenSubtitles · SubSense · SubtitleCat", urKeyless)

        try {
            System.setProperty("cloudstream.subsource.key", "test-ss-key")
            System.setProperty("cloudstream.wyzie.key", "test-wy-key")

            assertEquals(6, StreamPlayDesignTokens.countReadyEnglishSources(null, null))
            assertEquals(8, StreamPlayDesignTokens.countReadyUrduSources(null, null))

            val enWithKeys = StreamPlayDesignTokens.formatSubtitleSourcesLine(false, null, null)
            val urWithKeys = StreamPlayDesignTokens.formatSubtitleSourcesLine(true, null, null)

            assertEquals("OpenSubtitles · SubDL · Subf2m · YTS · SubtitleCat · SubSource", enWithKeys)
            assertEquals("SubDL · Subf2m · YTS · OpenSubtitles · SubSense · SubtitleCat · SubSource · Wyzie", urWithKeys)
        } finally {
            System.clearProperty("cloudstream.subsource.key")
            System.clearProperty("cloudstream.wyzie.key")
        }
    }

    @Test
    fun `preset profile encoding round-trips names containing colons pipes and commas without corruption`() {
        val original = linkedMapOf(
            "Fast: 720p | Low-bandwidth, v2" to setOf("superstream", "vidsrc"),
            "Anime | Sub: English, Urdu" to emptySet(),
            "Standard" to setOf("uhdmovies")
        )
        val encoded = ProvidersFragment.encodeProfilesMap(original)
        val decoded = ProvidersFragment.decodeProfilesMap(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `every layout and drawable resource is actively referenced in Kotlin or XML code`() {
        val root = resolveModuleRoot()
        val layoutDir = File(root, "src/main/res/layout")
        val drawableDir = File(root, "src/main/res/drawable")
        val kotlinRoot = File(root, "src/main/kotlin")

        val allCodeAndLayouts = (
            kotlinRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
                layoutDir.listFiles { f -> f.extension == "xml" }.orEmpty().toList()
            ).joinToString("\n") { it.readText() }

        for (layout in layoutDir.listFiles { f -> f.extension == "xml" }.orEmpty()) {
            val name = layout.nameWithoutExtension
            assertTrue("Layout $name must be referenced in Kotlin or XML", allCodeAndLayouts.contains(name))
        }

        for (drawable in drawableDir.listFiles { f -> f.extension == "xml" }.orEmpty()) {
            val name = drawable.nameWithoutExtension
            assertTrue("Drawable $name must be referenced in Kotlin or XML", allCodeAndLayouts.contains(name))
        }
    }

    @Test
    fun `provider list filtering and state summary computation execute in under 5ms`() {
        val providers = buildProviders().sortedBy { it.name.lowercase() }
        assertEquals(10, providers.size)

        val startNanos = System.nanoTime()
        repeat(50) {
            val query = "vid"
            val matched = providers.count { it.name.lowercase().contains(query) }
            assertTrue(matched > 0)
            StreamPlayDesignTokens.formatConcurrencySummary(16)
            StreamPlayDesignTokens.formatSubtitleSourcesLine(true, null, null)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0 / 50.0
        assertTrue("Average filter + summary latency ($elapsedMs ms) must be < 5.0 ms", elapsedMs < 5.0)
    }
}
