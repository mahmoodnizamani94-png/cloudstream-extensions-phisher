package com.phisher98

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.StateSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

/**
 * Single source of truth for StreamPlay Settings design tokens (OKLCH-calibrated dark palette,
 * modular typography, 4dp spacing grid, nested radius scale, TV D-pad focus rings, and
 * reduced-motion-aware transitions).
 *
 * Synced with `DESIGN.md`.
 */
object StreamPlayDesignTokens {

    // -------------------------------------------------------------------------
    // 1. Color Tokens (OKLCH-calibrated cool obsidian dark palette)
    // -------------------------------------------------------------------------
    const val HEX_SURFACE_CANVAS = "#0E1116"       // oklch(0.17 0.010 255)
    const val HEX_SURFACE_CARD = "#171C24"         // oklch(0.22 0.012 255)
    const val HEX_SURFACE_CARD_PRESSED = "#212834" // oklch(0.27 0.015 255)
    const val HEX_SURFACE_TRACK = "#0B0E13"        // oklch(0.15 0.010 255)
    const val HEX_SURFACE_OVERLAY = "#1D2430"      // oklch(0.25 0.014 255)
    const val HEX_SURFACE_OVERLAY_PRESSED = "#283242"

    const val HEX_BORDER_SUBTLE = "#283140"        // oklch(0.30 0.012 255)
    const val HEX_BORDER_FOCUS = "#E2E8F0"         // oklch(0.92 0.010 255)

    const val HEX_TEXT_PRIMARY = "#F1F5F9"         // 17.26:1 vs canvas (WCAG AAA)
    const val HEX_TEXT_SECONDARY = "#94A3B8"       // 6.67:1 vs card (WCAG AA)
    const val HEX_TEXT_MUTED = "#7C8B9E"           // 5.44:1 vs canvas / 5.56:1 vs track (WCAG AA)

    const val HEX_ACCENT_PRIMARY = "#2563EB"       // 5.17:1 with #FFFFFF on-accent (WCAG AA)
    const val HEX_ACCENT_PRIMARY_PRESSED = "#1D4ED8"
    const val HEX_ACCENT_SOFT = "#93C5FD"          // 9.48:1 vs card (WCAG AAA)

    const val HEX_DANGER_SURFACE = "#3B181E"       // Quiet semantic danger surface
    const val HEX_DANGER_SURFACE_PRESSED = "#4C1D24"
    const val HEX_DANGER_BORDER = "#7F1D1D"
    const val HEX_DANGER_TEXT = "#FCA5A5"          // 8.29:1 vs dangerSurface (WCAG AAA)

    const val SURFACE_CANVAS: Int = 0xFF0E1116.toInt()
    const val SURFACE_CARD: Int = 0xFF171C24.toInt()
    const val SURFACE_CARD_PRESSED: Int = 0xFF212834.toInt()
    const val SURFACE_TRACK: Int = 0xFF0B0E13.toInt()
    const val SURFACE_OVERLAY: Int = 0xFF1D2430.toInt()
    const val SURFACE_OVERLAY_PRESSED: Int = 0xFF283242.toInt()

    const val BORDER_SUBTLE: Int = 0xFF283140.toInt()
    const val BORDER_FOCUS: Int = 0xFFE2E8F0.toInt()

    const val TEXT_PRIMARY: Int = 0xFFF1F5F9.toInt()
    const val TEXT_SECONDARY: Int = 0xFF94A3B8.toInt()
    const val TEXT_MUTED: Int = 0xFF7C8B9E.toInt()
    const val ON_ACCENT: Int = 0xFFFFFFFF.toInt()

    const val ACCENT_PRIMARY: Int = 0xFF2563EB.toInt()
    const val ACCENT_PRIMARY_PRESSED: Int = 0xFF1D4ED8.toInt()
    const val ACCENT_SOFT: Int = 0xFF93C5FD.toInt()

    const val DANGER_SURFACE: Int = 0xFF3B181E.toInt()
    const val DANGER_SURFACE_PRESSED: Int = 0xFF4C1D24.toInt()
    const val DANGER_BORDER: Int = 0xFF7F1D1D.toInt()
    const val DANGER_TEXT: Int = 0xFFFCA5A5.toInt()

    // Semantic aliases used across settings fragments and verification tests
    const val SURFACE_ELEVATED: Int = SURFACE_CARD
    const val SURFACE_HIGH: Int = SURFACE_OVERLAY
    const val SURFACE_CONTROL: Int = BORDER_SUBTLE
    const val ACCENT_SUBTLE: Int = 0x332563EB
    const val STATUS_DANGER: Int = DANGER_TEXT

    // -------------------------------------------------------------------------
    // 2. Radius & Dimension Scale (Nested Radius Rule: outer = inner + padding)
    // -------------------------------------------------------------------------
    const val RADIUS_DIALOG_DP = 20f
    const val RADIUS_CARD_DP = 14f
    const val RADIUS_SECTION_CARD_DP = 14f
    const val RADIUS_TRACK_DP = 12f
    const val RADIUS_ROW_DP = 12f
    const val RADIUS_SEGMENT_DP = 9f // 12dp track radius - 3dp track padding = 9dp
    const val RADIUS_SEGMENT_INNER_DP = 9f
    const val RADIUS_BUTTON_DP = 10f
    const val RADIUS_INPUT_DP = 10f
    const val MIN_TOUCH_TARGET_DP = 48

    const val MOTION_MICRO_MS = 150L
    const val MAX_DIALOG_WIDTH_DP = 500

    enum class ButtonVariant {
        PRIMARY,
        SECONDARY,
        DANGER,
        ICON_GHOST
    }

    fun dpToPx(density: Float, dp: Float): Int = (dp * density).roundToInt()

    fun dp(resources: android.content.res.Resources, dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    fun dialogSurfaceDrawable(resources: android.content.res.Resources): Drawable =
        dialogSheetDrawable(resources.displayMetrics.density)

    fun elevatedCardDrawable(resources: android.content.res.Resources): Drawable =
        staticCardDrawable(resources.displayMetrics.density)

    fun interactiveRowDrawable(resources: android.content.res.Resources): Drawable =
        cardSurfaceDrawable(resources.displayMetrics.density)

    fun iconButtonDrawable(resources: android.content.res.Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.ICON_GHOST)

    fun primaryButtonDrawable(resources: android.content.res.Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.PRIMARY)

    fun secondaryButtonDrawable(resources: android.content.res.Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.SECONDARY)

    fun destructiveOutlineButtonDrawable(resources: android.content.res.Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.DANGER)

    fun inputFieldDrawable(resources: android.content.res.Resources): Drawable =
        inputWellDrawable(resources.displayMetrics.density)

    fun formatConcurrencySummary(concurrency: Int): String {
        return "$concurrency · ${StreamPlayConcurrency.concurrencyTier(concurrency)}"
    }

    fun compoundButtonTintList(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            StateSet.WILD_CARD
        ),
        intArrayOf(TEXT_MUTED, ACCENT_PRIMARY, TEXT_SECONDARY)
    )

    fun switchThumbTintList(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            StateSet.WILD_CARD
        ),
        intArrayOf(TEXT_MUTED, ON_ACCENT, TEXT_SECONDARY)
    )

    fun switchTrackTintList(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            StateSet.WILD_CARD
        ),
        intArrayOf(SURFACE_TRACK, ACCENT_PRIMARY, BORDER_SUBTLE)
    )

    @Suppress("DEPRECATION")
    fun configureDialogWindow(
        dialog: android.app.Dialog?,
        resources: android.content.res.Resources,
        widthFraction: Float = 0.92f,
        maxHeightFraction: Float = 0.90f
    ) {
        val window = dialog?.window ?: return
        val displayMetrics = resources.displayMetrics
        val maxDialogWidth = dpToPx(displayMetrics.density, MAX_DIALOG_WIDTH_DP.toFloat())
        val width = if (displayMetrics.widthPixels > 0 && displayMetrics.widthPixels > maxDialogWidth) {
            maxDialogWidth
        } else if (displayMetrics.widthPixels > 0) {
            (displayMetrics.widthPixels * widthFraction).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val maxDialogHeight = (displayMetrics.heightPixels * maxHeightFraction).toInt()
        window.setGravity(android.view.Gravity.CENTER)
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        runCatching {
            window.navigationBarColor = SURFACE_CANVAS
        }
        if (maxDialogHeight > 0) {
            window.decorView.post {
                if (window.decorView.height > maxDialogHeight) {
                    window.setLayout(width, maxDialogHeight)
                }
            }
        }
    }

    fun applyWindowInsetsPadding(view: View) {
        val basePaddingLeft = view.paddingLeft
        val basePaddingTop = view.paddingTop
        val basePaddingRight = view.paddingRight
        val basePaddingBottom = view.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                basePaddingLeft + insets.left,
                basePaddingTop + insets.top,
                basePaddingRight + insets.right,
                basePaddingBottom + insets.bottom
            )
            windowInsets
        }
    }

    /**
     * Configures horizontal D-pad focus links between startView and endView, automatically reversing
     * Left/Right focus IDs when the parent layout direction is RTL (`LAYOUT_DIRECTION_RTL`).
     */
    fun applyDirectionalFocus(startView: View?, endView: View?) {
        if (startView == null || endView == null) return
        startView.post {
            val isRtl = startView.layoutDirection == View.LAYOUT_DIRECTION_RTL
            if (isRtl) {
                startView.nextFocusLeftId = endView.id
                endView.nextFocusRightId = startView.id
            } else {
                startView.nextFocusRightId = endView.id
                endView.nextFocusLeftId = startView.id
            }
        }
    }

    fun restartHostApp(context: Context) {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val intent = packageManager.getLaunchIntentForPackage(appContext.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = android.content.Intent.makeRestartActivityTask(componentName)
            appContext.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    // -------------------------------------------------------------------------
    // 3. BiDi Isolation Helper (Prevents RTL/LTR parenthesis & punctuation swaps)
    // -------------------------------------------------------------------------
    private const val FSI = '\u2068' // First Strong Isolate
    private const val RLI = '\u2067' // Right-to-Left Isolate
    private const val PDI = '\u2069' // Pop Directional Isolate

    /**
     * Wraps Urdu script in Unicode Right-to-Left Isolate (`\u2067...\u2069`) so mixed English + Urdu
     * labels never invert adjacent parentheses or punctuation under Android's LTR Bidi layout.
     */
    fun isolateRtl(urduText: String): String = "$RLI$urduText$PDI"

    fun isolateFsi(text: String): String = "$FSI$text$PDI"

    // -------------------------------------------------------------------------
    // 4. Programmatic Drawable Builders (Safe across CloudStream plugin loader)
    // -------------------------------------------------------------------------
    fun buildShape(
        density: Float,
        fillColor: Int,
        cornerRadiusDp: Float,
        strokeWidthDp: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = density * cornerRadiusDp
            setColor(fillColor)
            if (strokeWidthDp > 0f) {
                setStroke((density * strokeWidthDp).roundToInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    fun dialogSheetDrawable(density: Float): Drawable {
        return buildShape(
            density = density,
            fillColor = SURFACE_CANVAS,
            cornerRadiusDp = RADIUS_DIALOG_DP,
            strokeWidthDp = 1f,
            strokeColor = BORDER_SUBTLE
        )
    }

    fun cardSurfaceDrawable(density: Float): Drawable {
        val stateList = StateListDrawable()
        stateList.addState(
            intArrayOf(android.R.attr.state_focused),
            buildShape(density, SURFACE_CARD_PRESSED, RADIUS_CARD_DP, 2f, BORDER_FOCUS)
        )
        stateList.addState(
            intArrayOf(android.R.attr.state_pressed),
            buildShape(density, SURFACE_CARD_PRESSED, RADIUS_CARD_DP, 1f, BORDER_SUBTLE)
        )
        stateList.addState(
            StateSet.WILD_CARD,
            buildShape(density, SURFACE_CARD, RADIUS_CARD_DP, 1f, BORDER_SUBTLE)
        )
        val mask = buildShape(density, Color.WHITE, RADIUS_CARD_DP)
        return RippleDrawable(ColorStateList.valueOf(0x2693C5FD), stateList, mask)
    }

    fun staticCardDrawable(density: Float): Drawable {
        val stateList = StateListDrawable()
        stateList.addState(
            intArrayOf(android.R.attr.state_focused),
            buildShape(density, SURFACE_CARD, RADIUS_CARD_DP, 2f, BORDER_FOCUS)
        )
        stateList.addState(
            StateSet.WILD_CARD,
            buildShape(density, SURFACE_CARD, RADIUS_CARD_DP, 1f, BORDER_SUBTLE)
        )
        return stateList
    }

    fun segmentedTrackDrawable(density: Float): Drawable {
        return buildShape(
            density = density,
            fillColor = SURFACE_TRACK,
            cornerRadiusDp = RADIUS_TRACK_DP,
            strokeWidthDp = 1f,
            strokeColor = BORDER_SUBTLE
        )
    }

    fun segmentOptionDrawable(density: Float, selected: Boolean): Drawable {
        val stateList = StateListDrawable()
        if (selected) {
            stateList.addState(
                intArrayOf(android.R.attr.state_focused),
                buildShape(density, ACCENT_PRIMARY, RADIUS_SEGMENT_DP, 2f, BORDER_FOCUS)
            )
            stateList.addState(
                intArrayOf(android.R.attr.state_pressed),
                buildShape(density, ACCENT_PRIMARY_PRESSED, RADIUS_SEGMENT_DP)
            )
            stateList.addState(
                StateSet.WILD_CARD,
                buildShape(density, ACCENT_PRIMARY, RADIUS_SEGMENT_DP)
            )
        } else {
            stateList.addState(
                intArrayOf(android.R.attr.state_focused),
                buildShape(density, SURFACE_CARD_PRESSED, RADIUS_SEGMENT_DP, 2f, BORDER_FOCUS)
            )
            stateList.addState(
                intArrayOf(android.R.attr.state_pressed),
                buildShape(density, SURFACE_CARD_PRESSED, RADIUS_SEGMENT_DP)
            )
            stateList.addState(
                StateSet.WILD_CARD,
                buildShape(density, Color.TRANSPARENT, RADIUS_SEGMENT_DP)
            )
        }
        return stateList
    }

    fun buttonDrawable(density: Float, variant: ButtonVariant): Drawable {
        val stateList = StateListDrawable()
        when (variant) {
            ButtonVariant.PRIMARY -> {
                stateList.addState(
                    intArrayOf(-android.R.attr.state_enabled),
                    buildShape(density, SURFACE_OVERLAY, RADIUS_BUTTON_DP, 1f, BORDER_SUBTLE)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_focused),
                    buildShape(density, ACCENT_PRIMARY, RADIUS_BUTTON_DP, 2f, BORDER_FOCUS)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_pressed),
                    buildShape(density, ACCENT_PRIMARY_PRESSED, RADIUS_BUTTON_DP)
                )
                stateList.addState(
                    StateSet.WILD_CARD,
                    buildShape(density, ACCENT_PRIMARY, RADIUS_BUTTON_DP)
                )
            }
            ButtonVariant.SECONDARY -> {
                stateList.addState(
                    intArrayOf(-android.R.attr.state_enabled),
                    buildShape(density, SURFACE_TRACK, RADIUS_BUTTON_DP, 1f, BORDER_SUBTLE)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_focused),
                    buildShape(density, SURFACE_OVERLAY_PRESSED, RADIUS_BUTTON_DP, 2f, BORDER_FOCUS)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_pressed),
                    buildShape(density, SURFACE_OVERLAY_PRESSED, RADIUS_BUTTON_DP, 1f, BORDER_SUBTLE)
                )
                stateList.addState(
                    StateSet.WILD_CARD,
                    buildShape(density, SURFACE_OVERLAY, RADIUS_BUTTON_DP, 1f, BORDER_SUBTLE)
                )
            }
            ButtonVariant.DANGER -> {
                stateList.addState(
                    intArrayOf(-android.R.attr.state_enabled),
                    buildShape(density, SURFACE_TRACK, RADIUS_BUTTON_DP, 1f, BORDER_SUBTLE)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_focused),
                    buildShape(density, DANGER_SURFACE_PRESSED, RADIUS_BUTTON_DP, 2f, BORDER_FOCUS)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_pressed),
                    buildShape(density, DANGER_SURFACE_PRESSED, RADIUS_BUTTON_DP, 1f, DANGER_BORDER)
                )
                stateList.addState(
                    StateSet.WILD_CARD,
                    buildShape(density, DANGER_SURFACE, RADIUS_BUTTON_DP, 1f, DANGER_BORDER)
                )
            }
            ButtonVariant.ICON_GHOST -> {
                stateList.addState(
                    intArrayOf(android.R.attr.state_focused),
                    buildShape(density, SURFACE_CARD_PRESSED, RADIUS_BUTTON_DP, 2f, BORDER_FOCUS)
                )
                stateList.addState(
                    intArrayOf(android.R.attr.state_pressed),
                    buildShape(density, SURFACE_CARD_PRESSED, RADIUS_BUTTON_DP)
                )
                stateList.addState(
                    StateSet.WILD_CARD,
                    buildShape(density, Color.TRANSPARENT, RADIUS_BUTTON_DP)
                )
            }
        }
        return stateList
    }

    fun styleButton(button: android.widget.TextView, density: Float, variant: ButtonVariant) {
        button.backgroundTintList = null
        button.background = buttonDrawable(density, variant)
        button.isAllCaps = false
        val textColors = when (variant) {
            ButtonVariant.PRIMARY -> ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), StateSet.WILD_CARD),
                intArrayOf(TEXT_MUTED, ON_ACCENT)
            )
            ButtonVariant.SECONDARY, ButtonVariant.ICON_GHOST -> ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), StateSet.WILD_CARD),
                intArrayOf(TEXT_MUTED, TEXT_PRIMARY)
            )
            ButtonVariant.DANGER -> ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), StateSet.WILD_CARD),
                intArrayOf(TEXT_MUTED, DANGER_TEXT)
            )
        }
        button.setTextColor(textColors)
    }

    fun inputWellDrawable(density: Float): Drawable {
        val stateList = StateListDrawable()
        stateList.addState(
            intArrayOf(android.R.attr.state_focused),
            buildShape(density, SURFACE_TRACK, RADIUS_INPUT_DP, 2f, ACCENT_PRIMARY)
        )
        stateList.addState(
            StateSet.WILD_CARD,
            buildShape(density, SURFACE_TRACK, RADIUS_INPUT_DP, 1f, BORDER_SUBTLE)
        )
        return stateList
    }

    fun listItemDrawable(density: Float): Drawable {
        val stateList = StateListDrawable()
        stateList.addState(
            intArrayOf(android.R.attr.state_focused),
            buildShape(density, SURFACE_CARD_PRESSED, RADIUS_BUTTON_DP, 2f, BORDER_FOCUS)
        )
        stateList.addState(
            intArrayOf(android.R.attr.state_pressed),
            buildShape(density, SURFACE_CARD_PRESSED, RADIUS_BUTTON_DP)
        )
        stateList.addState(
            intArrayOf(android.R.attr.state_checked),
            buildShape(density, SURFACE_CARD, RADIUS_BUTTON_DP, 1f, BORDER_SUBTLE)
        )
        stateList.addState(
            StateSet.WILD_CARD,
            buildShape(density, Color.TRANSPARENT, RADIUS_BUTTON_DP)
        )
        return stateList
    }

    // -------------------------------------------------------------------------
    // 5. Reduced-Motion-Aware Choreographed Transition (§7)
    // -------------------------------------------------------------------------
    fun isReducedMotionEnabled(context: Context?): Boolean {
        if (context == null) return false
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        return scale <= 0.01f || !ValueAnimator.areAnimatorsEnabled()
    }

    fun beginMicroTransition(container: ViewGroup?, context: Context?) {
        if (container == null || isReducedMotionEnabled(context)) return
        runCatching {
            val transition = AutoTransition().apply {
                duration = MOTION_MICRO_MS
                interpolator = DecelerateInterpolator()
            }
            TransitionManager.beginDelayedTransition(container, transition)
        }
    }

    // -------------------------------------------------------------------------
    // 6. Live State Summary Resolvers for MainSettingsFragment
    // -------------------------------------------------------------------------
    fun countConfiguredSubtitleKeys(
        context: Context?,
        sharedPref: android.content.SharedPreferences?
    ): Int {
        var count = 0
        if (!UrduSubtitleEngine.resolveWyzieApiKey(context, sharedPref).isNullOrBlank()) count++
        if (!UrduSubtitleEngine.resolveSubDlApiKey(context, sharedPref).isNullOrBlank()) count++
        if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) count++
        return count
    }

    fun countReadyUrduSources(
        context: Context?,
        sharedPref: android.content.SharedPreferences?
    ): Int {
        // 6 keyless sources (SubDL, SubSense, Subf2m, YTS, OpenSubtitles, SubtitleCat) + 2 key-gated (SubSource, Wyzie)
        var ready = 6
        if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) ready++
        if (!UrduSubtitleEngine.resolveWyzieApiKey(context, sharedPref).isNullOrBlank()) ready++
        return ready.coerceAtMost(UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES)
    }

    fun countReadyEnglishSources(
        context: Context?,
        sharedPref: android.content.SharedPreferences?
    ): Int {
        // 5 keyless English sources (OpenSubtitles, SubDL, Subf2m, YTS, SubtitleCat) + 1 key-gated (SubSource)
        var ready = 5
        if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) ready++
        return ready.coerceAtMost(6)
    }

    fun formatSubtitleSourcesLine(
        isUrdu: Boolean,
        context: Context?,
        sharedPref: android.content.SharedPreferences?
    ): String {
        return if (isUrdu) {
            buildList {
                add("SubDL")
                add("Subf2m")
                add("YTS")
                add("OpenSubtitles")
                add("SubSense")
                add("SubtitleCat")
                if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) {
                    add("SubSource")
                }
                if (!UrduSubtitleEngine.resolveWyzieApiKey(context, sharedPref).isNullOrBlank()) {
                    add("Wyzie")
                }
            }.joinToString(" · ")
        } else {
            buildList {
                add("OpenSubtitles")
                add("SubDL")
                add("Subf2m")
                add("YTS")
                add("SubtitleCat")
                if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) {
                    add("SubSource")
                }
            }.joinToString(" · ")
        }
    }
}
