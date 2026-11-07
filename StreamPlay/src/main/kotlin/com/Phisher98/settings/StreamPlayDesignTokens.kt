package com.phisher98

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.StateSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Single source of truth for StreamPlay Settings design tokens (OKLCH-calibrated dark palette,
 * modular typography, 4dp spacing grid, nested radius scale, grouped inset section surfaces,
 * TV D-pad focus rings, reduced-motion-aware transitions, and tokenized modal dialogs).
 *
 * Synced with `DESIGN.md`.
 */
object StreamPlayDesignTokens {

    const val FRAGMENT_RESULT_SETTINGS_CHANGED = "streamplay_settings_changed"

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

    enum class GroupPosition {
        SINGLE,
        TOP,
        MIDDLE,
        BOTTOM
    }

    fun dpToPx(density: Float, dp: Float): Int = (dp * density).roundToInt()

    fun dp(resources: Resources, dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    fun resolvePluginResources(fragment: Fragment, explicitPlugin: StreamPlayPlugin?): Resources {
        val pluginRes = explicitPlugin?.resources ?: StreamPlayPlugin.instance?.resources
        return pluginRes ?: fragment.requireContext().resources
    }

    fun resolveSharedPreferences(fragment: Fragment, explicitPrefs: SharedPreferences?): SharedPreferences {
        return explicitPrefs ?: fragment.requireContext().getSharedPreferences(
            StreamPlayPlugin.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
    }

    fun notifyParentSettingsChanged(fragment: DialogFragment, explicitCallback: (() -> Unit)?) {
        explicitCallback?.invoke()
        runCatching {
            if (fragment.isAdded) {
                fragment.parentFragmentManager.setFragmentResult(
                    FRAGMENT_RESULT_SETTINGS_CHANGED,
                    android.os.Bundle.EMPTY
                )
            }
        }
    }

    fun dialogSurfaceDrawable(resources: Resources): Drawable =
        dialogSheetDrawable(resources.displayMetrics.density)

    fun elevatedCardDrawable(resources: Resources): Drawable =
        staticCardDrawable(resources.displayMetrics.density)

    fun interactiveRowDrawable(resources: Resources): Drawable =
        cardSurfaceDrawable(resources.displayMetrics.density)

    fun iconButtonDrawable(resources: Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.ICON_GHOST)

    fun primaryButtonDrawable(resources: Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.PRIMARY)

    fun secondaryButtonDrawable(resources: Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.SECONDARY)

    fun destructiveOutlineButtonDrawable(resources: Resources): Drawable =
        buttonDrawable(resources.displayMetrics.density, ButtonVariant.DANGER)

    fun inputFieldDrawable(resources: Resources): Drawable =
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
        dialog: Dialog?,
        resources: Resources,
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
        val verticalInset = if (displayMetrics.heightPixels > 0) {
            ((displayMetrics.heightPixels * (1f - maxHeightFraction).coerceAtLeast(0f)) / 2f).roundToInt()
        } else {
            0
        }
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawable(
            InsetDrawable(ColorDrawable(Color.TRANSPARENT), 0, verticalInset, 0, verticalInset)
        )
        runCatching {
            window.navigationBarColor = SURFACE_CANVAS
        }
    }

    fun applyWindowInsetsPadding(view: View) {
        val basePaddingLeft = view.paddingLeft
        val basePaddingTop = view.paddingTop
        val basePaddingRight = view.paddingRight
        val basePaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val systemInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                basePaddingLeft + systemInsets.left,
                basePaddingTop + systemInsets.top,
                basePaddingRight + systemInsets.right,
                basePaddingBottom + max(systemInsets.bottom, imeInsets.bottom)
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

    fun buildCornerRadiiShape(
        density: Float,
        fillColor: Int,
        topLeftDp: Float,
        topRightDp: Float,
        bottomRightDp: Float,
        bottomLeftDp: Float,
        strokeWidthDp: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        val tl = density * topLeftDp
        val tr = density * topRightDp
        val br = density * bottomRightDp
        val bl = density * bottomLeftDp
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
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

    /**
     * Unified OS-level grouped section container (`#171C24` fill, `14dp` radius, `1dp` `#283140` border).
     * Holds multiple sibling rows separated by `1dp` hairline dividers so rows never float as
     * repetitive individual bordered rectangles.
     */
    fun groupedSectionDrawable(density: Float): Drawable {
        return buildShape(
            density = density,
            fillColor = SURFACE_CARD,
            cornerRadiusDp = RADIUS_SECTION_CARD_DP,
            strokeWidthDp = 1f,
            strokeColor = BORDER_SUBTLE
        )
    }

    /**
     * Interactive row surface inside a `groupedSectionDrawable` container.
     * Transparent at rest so the parent section card provides the unified plane and border,
     * with position-matched corner radii (`14dp` on outer corners, `0dp` on inner seams) when
     * pressed or focused via D-pad.
     */
    fun groupedRowDrawable(
        density: Float,
        position: GroupPosition,
        showBottomDivider: Boolean = false
    ): Drawable {
        val topR = when (position) {
            GroupPosition.SINGLE, GroupPosition.TOP -> RADIUS_SECTION_CARD_DP
            GroupPosition.MIDDLE, GroupPosition.BOTTOM -> 0f
        }
        val botR = when (position) {
            GroupPosition.SINGLE, GroupPosition.BOTTOM -> RADIUS_SECTION_CARD_DP
            GroupPosition.TOP, GroupPosition.MIDDLE -> 0f
        }
        val stateList = StateListDrawable()
        stateList.addState(
            intArrayOf(android.R.attr.state_focused),
            buildCornerRadiiShape(density, SURFACE_CARD_PRESSED, topR, topR, botR, botR, 2f, BORDER_FOCUS)
        )
        stateList.addState(
            intArrayOf(android.R.attr.state_pressed),
            buildCornerRadiiShape(density, SURFACE_CARD_PRESSED, topR, topR, botR, botR)
        )
        stateList.addState(
            StateSet.WILD_CARD,
            buildCornerRadiiShape(density, Color.TRANSPARENT, topR, topR, botR, botR)
        )
        val mask = buildCornerRadiiShape(density, Color.WHITE, topR, topR, botR, botR)
        val ripple = RippleDrawable(ColorStateList.valueOf(0x2293C5FD), stateList, mask)
        if (!showBottomDivider || position == GroupPosition.BOTTOM || position == GroupPosition.SINGLE) {
            return ripple
        }
        val dividerHeightPx = dpToPx(density, 1f).coerceAtLeast(1)
        val insetPx = dpToPx(density, 16f)
        val dividerShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(BORDER_SUBTLE)
            setSize(0, dividerHeightPx)
        }
        return LayerDrawable(arrayOf(ripple, dividerShape)).apply {
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, dividerHeightPx)
            setLayerInsetRelative(1, insetPx, 0, 0, 0)
        }
    }

    /**
     * Attaches an idempotent `1dp` `#283140` (`BORDER_SUBTLE`) hairline `ItemDecoration` inset `16dp`
     * from the start edge (`LTR`/`RTL` aware) between items in a grouped `RecyclerView`.
     */
    fun attachGroupedListDivider(recyclerView: RecyclerView, density: Float) {
        for (i in recyclerView.itemDecorationCount - 1 downTo 0) {
            if (recyclerView.getItemDecorationAt(i) is GroupedInsetDividerDecoration) {
                recyclerView.removeItemDecorationAt(i)
            }
        }
        recyclerView.addItemDecoration(GroupedInsetDividerDecoration(density))
    }

    private class GroupedInsetDividerDecoration(density: Float) : RecyclerView.ItemDecoration() {
        private val dividerHeightPx = dpToPx(density, 1f).coerceAtLeast(1)
        private val insetStartPx = dpToPx(density, 16f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_SUBTLE
            style = Paint.Style.FILL
        }

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val itemCount = parent.adapter?.itemCount ?: state.itemCount
            if (position != RecyclerView.NO_POSITION && position < itemCount - 1) {
                outRect.set(0, 0, 0, dividerHeightPx)
            } else {
                outRect.set(0, 0, 0, 0)
            }
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val itemCount = parent.adapter?.itemCount ?: state.itemCount
            if (itemCount <= 1) return
            val isRtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val left = if (isRtl) parent.paddingLeft else parent.paddingLeft + insetStartPx
            val right = if (isRtl) parent.width - parent.paddingRight - insetStartPx else parent.width - parent.paddingRight
            if (right <= left) return
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION && position < itemCount - 1) {
                    val params = child.layoutParams as? RecyclerView.LayoutParams
                    val top = child.bottom + (params?.bottomMargin ?: 0)
                    val bottom = top + dividerHeightPx
                    c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
                }
            }
        }
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
                    intArrayOf(-android.R.attr.state_enabled),
                    buildShape(density, Color.TRANSPARENT, RADIUS_BUTTON_DP)
                )
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

    fun styleButton(button: TextView, density: Float, variant: ButtonVariant) {
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
    // 5. Tokenized OS-Environment Modal Dialogs (Zero Raw AlertDialog Escapes)
    // -------------------------------------------------------------------------
    fun createThemedCustomDialog(
        context: Context,
        resources: Resources,
        contentView: View,
        maxHeightFraction: Float = 0.85f,
        onShown: ((Dialog) -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        contentView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        contentView.background = dialogSheetDrawable(resources.displayMetrics.density)
        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            configureDialogWindow(dialog, resources, maxHeightFraction = maxHeightFraction)
            onShown?.invoke(dialog)
        }
        applyWindowInsetsPadding(contentView)
        return dialog
    }

    fun showConfirmationDialog(
        context: Context,
        resources: Resources,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String = "Cancel",
        confirmVariant: ButtonVariant = ButtonVariant.PRIMARY,
        onConfirm: () -> Unit
    ): Dialog {
        val density = resources.displayMetrics.density
        val pad = dpToPx(density, 20f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(context).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.015f
        }
        val messageView = TextView(context).apply {
            text = message
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dpToPx(density, 2f).toFloat(), 1f)
            setPadding(0, dpToPx(density, 6f), 0, dpToPx(density, 18f))
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancelBtn = Button(context).apply {
            id = View.generateViewId()
            text = cancelText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
            styleButton(this, density, ButtonVariant.SECONDARY)
        }
        val confirmBtn = Button(context).apply {
            id = View.generateViewId()
            text = confirmText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
            styleButton(this, density, confirmVariant)
        }
        actionsRow.addView(
            cancelBtn,
            LinearLayout.LayoutParams(0, dpToPx(density, 48f), 1f)
        )
        actionsRow.addView(
            Space(context),
            LinearLayout.LayoutParams(dpToPx(density, 10f), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        actionsRow.addView(
            confirmBtn,
            LinearLayout.LayoutParams(0, dpToPx(density, 48f), 1f)
        )
        applyDirectionalFocus(cancelBtn, confirmBtn)

        root.addView(titleView)
        root.addView(messageView)
        root.addView(actionsRow)

        val scroll = ScrollView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            isFillViewport = true
            addView(root)
        }
        val dialog = createThemedCustomDialog(context, resources, scroll)
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
        return dialog
    }

    fun showTextInputDialog(
        context: Context,
        resources: Resources,
        title: String,
        subtitle: String,
        fieldLabel: String,
        hintText: String,
        confirmText: String,
        cancelText: String = "Cancel",
        initialText: String = "",
        onTextChanged: ((String) -> Unit)? = null,
        onConfirm: (String) -> Unit
    ): Dialog {
        val density = resources.displayMetrics.density
        val pad = dpToPx(density, 20f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(context).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.015f
        }
        val subtitleView = TextView(context).apply {
            text = subtitle
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dpToPx(density, 4f), 0, dpToPx(density, 14f))
        }
        val inputId = View.generateViewId()
        val labelView = TextView(context).apply {
            text = fieldLabel
            labelFor = inputId
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(density, 6f))
        }
        val padH = dpToPx(density, 14f)
        val padV = dpToPx(density, 12f)
        val input = EditText(context).apply {
            id = inputId
            hint = hintText
            setSingleLine()
            minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = inputWellDrawable(density)
            setPadding(padH, padV, padH, padV)
            if (initialText.isNotEmpty()) {
                setText(initialText)
                setSelection(initialText.length)
            }
            if (onTextChanged != null) {
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        onTextChanged(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(density, 16f), 0, 0)
        }
        val cancelBtn = Button(context).apply {
            id = View.generateViewId()
            text = cancelText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
            styleButton(this, density, ButtonVariant.SECONDARY)
        }
        val confirmBtn = Button(context).apply {
            id = View.generateViewId()
            text = confirmText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
            styleButton(this, density, ButtonVariant.PRIMARY)
        }
        actionsRow.addView(
            cancelBtn,
            LinearLayout.LayoutParams(0, dpToPx(density, 48f), 1f)
        )
        actionsRow.addView(
            Space(context),
            LinearLayout.LayoutParams(dpToPx(density, 10f), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        actionsRow.addView(
            confirmBtn,
            LinearLayout.LayoutParams(0, dpToPx(density, 48f), 1f)
        )
        applyDirectionalFocus(cancelBtn, confirmBtn)

        root.addView(titleView)
        root.addView(subtitleView)
        root.addView(labelView)
        root.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(actionsRow)

        val scroll = ScrollView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            isFillViewport = true
            addView(root)
        }
        val dialog = createThemedCustomDialog(context, resources, scroll) {
            input.isFocusableInTouchMode = true
            input.requestFocus()
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val value = input.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            onConfirm(value)
        }
        dialog.show()
        return dialog
    }

    fun showSelectionListDialog(
        context: Context,
        resources: Resources,
        title: String,
        subtitle: String,
        items: List<String>,
        itemTextColor: Int = TEXT_PRIMARY,
        cancelText: String = "Cancel",
        onSelect: (Int, String) -> Unit
    ): Dialog {
        val density = resources.displayMetrics.density
        val pad = dpToPx(density, 20f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(context).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.015f
        }
        val subtitleView = TextView(context).apply {
            text = subtitle
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dpToPx(density, 4f), 0, dpToPx(density, 14f))
        }
        val groupContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            background = groupedSectionDrawable(density)
        }
        val cancelBtn = Button(context).apply {
            id = View.generateViewId()
            text = cancelText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
            styleButton(this, density, ButtonVariant.SECONDARY)
        }

        root.addView(titleView)
        root.addView(subtitleView)
        root.addView(
            groupContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            cancelBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(density, 48f)).apply {
                topMargin = dpToPx(density, 14f)
            }
        )

        val scroll = ScrollView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            isFillViewport = true
            addView(root)
        }
        val dialog = createThemedCustomDialog(context, resources, scroll) {
            if (!groupContainer.isInTouchMode && groupContainer.childCount > 0) {
                groupContainer.getChildAt(0)?.requestFocus()
            }
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        items.forEachIndexed { index, label ->
            if (index > 0) {
                val divider = View(context).apply {
                    setBackgroundColor(BORDER_SUBTLE)
                }
                groupContainer.addView(
                    divider,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(density, 1f)).apply {
                        marginStart = dpToPx(density, 16f)
                    }
                )
            }
            val pos = when {
                items.size == 1 -> GroupPosition.SINGLE
                index == 0 -> GroupPosition.TOP
                index == items.lastIndex -> GroupPosition.BOTTOM
                else -> GroupPosition.MIDDLE
            }
            val row = TextView(context).apply {
                id = View.generateViewId()
                text = label
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                minHeight = dpToPx(density, MIN_TOUCH_TARGET_DP.toFloat())
                gravity = Gravity.CENTER_VERTICAL
                val rowPadH = dpToPx(density, 16f)
                val rowPadV = dpToPx(density, 12f)
                setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
                isClickable = true
                isFocusable = true
                background = groupedRowDrawable(density, pos)
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(index, label)
                }
            }
            groupContainer.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        dialog.show()
        return dialog
    }

    // -------------------------------------------------------------------------
    // 6. Reduced-Motion-Aware Choreographed Transition (§7)
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
    // 7. Live State Summary Resolvers for MainSettingsFragment
    // -------------------------------------------------------------------------
    fun countConfiguredSubtitleKeys(
        context: Context?,
        sharedPref: SharedPreferences?
    ): Int {
        var count = 0
        if (!UrduSubtitleEngine.resolveWyzieApiKey(context, sharedPref).isNullOrBlank()) count++
        if (!UrduSubtitleEngine.resolveSubDlApiKey(context, sharedPref).isNullOrBlank()) count++
        if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) count++
        return count
    }

    fun countReadyUrduSources(
        context: Context?,
        sharedPref: SharedPreferences?
    ): Int {
        // 6 keyless sources (SubDL, SubSense, Subf2m, YTS, OpenSubtitles, SubtitleCat) + 2 key-gated (SubSource, Wyzie)
        var ready = 6
        if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) ready++
        if (!UrduSubtitleEngine.resolveWyzieApiKey(context, sharedPref).isNullOrBlank()) ready++
        return ready.coerceAtMost(UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES)
    }

    fun countReadyEnglishSources(
        context: Context?,
        sharedPref: SharedPreferences?
    ): Int {
        // 5 keyless English sources (OpenSubtitles, SubDL, Subf2m, YTS, SubtitleCat) + 1 key-gated (SubSource)
        var ready = 5
        if (!UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref).isNullOrBlank()) ready++
        return ready.coerceAtMost(6)
    }

    fun formatSubtitleSourcesLine(
        isUrdu: Boolean,
        context: Context?,
        sharedPref: SharedPreferences?
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
