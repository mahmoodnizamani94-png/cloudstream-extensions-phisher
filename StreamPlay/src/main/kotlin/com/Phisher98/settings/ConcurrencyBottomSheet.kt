package com.phisher98

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment

class ConcurrencyBottomSheet(
    plugin: StreamPlayPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    // Single source of truth with proper clamp
    private var currentValue = sharedPref
        .getInt("provider_concurrency", StreamPlayConcurrency.DeviceProfile.MID_RANGE.recommendedConcurrency)
        .let(StreamPlayConcurrency::normalizeConcurrency)

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw IllegalStateException("View ID $name not found.")
        return this.findViewById(id)
    }

    private fun <T : View> View.findViewOrNull(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return runCatching { this.findViewById<T>(id) }.getOrNull()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = res.getIdentifier(
            "concurrency_bottom_sheet",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        )
        val layout = res.getLayout(layoutId)
        val view = inflater.inflate(layout, container, false)
        view.background = StreamPlayDesignTokens.dialogSheetDrawable(resources.displayMetrics.density)
        return view
    }

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)

        val rootContainer = view.findViewOrNull<ViewGroup>("concurrency_root")
        val stepperCard = view.findViewOrNull<View>("concurrency_stepper_card")
        val tvValue = view.findView<TextView>("tv_value")
        val btnDecrease = view.findView<Button>("btn_decrease")
        val btnIncrease = view.findView<Button>("btn_increase")
        val btnClose = view.findView<Button>("btn_close")
        val tvMode = view.findView<TextView>("tv_mode")
        val tvHint = view.findViewOrNull<TextView>("tv_concurrency_hint")

        val presetLow = view.findViewOrNull<TextView>("preset_low")
        val presetBalanced = view.findViewOrNull<TextView>("preset_balanced")
        val presetFast = view.findViewOrNull<TextView>("preset_fast")

        stepperCard?.background = StreamPlayDesignTokens.staticCardDrawable(density)
        StreamPlayDesignTokens.styleButton(btnDecrease, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnIncrease, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnClose, density, StreamPlayDesignTokens.ButtonVariant.PRIMARY)

        StreamPlayDesignTokens.applyDirectionalFocus(btnDecrease, btnIncrease)
        StreamPlayDesignTokens.applyDirectionalFocus(presetLow, presetBalanced)
        StreamPlayDesignTokens.applyDirectionalFocus(presetBalanced, presetFast)

        fun updatePresetButton(btn: TextView?, targetVal: Int) {
            btn ?: return
            val selected = currentValue == targetVal
            btn.background = StreamPlayDesignTokens.buttonDrawable(
                density,
                if (selected) StreamPlayDesignTokens.ButtonVariant.PRIMARY else StreamPlayDesignTokens.ButtonVariant.SECONDARY
            )
            btn.setTextColor(
                if (selected) StreamPlayDesignTokens.ON_ACCENT else StreamPlayDesignTokens.TEXT_PRIMARY
            )
        }

        fun updateUI() {
            tvValue.text = currentValue.toString()
            tvMode.text = StreamPlayConcurrency.concurrencyTier(currentValue)
            val atMin = currentValue <= StreamPlayConcurrency.MIN_PROVIDER_CONCURRENCY
            val atMax = currentValue >= StreamPlayConcurrency.MAX_PROVIDER_CONCURRENCY
            btnDecrease.isEnabled = !atMin
            btnIncrease.isEnabled = !atMax

            updatePresetButton(presetLow, 8)
            updatePresetButton(presetBalanced, 16)
            updatePresetButton(presetFast, 32)

            tvHint?.text = when {
                atMin -> "Minimum limit reached (${StreamPlayConcurrency.MIN_PROVIDER_CONCURRENCY} tasks). Keeps slow connections stable."
                atMax -> "Maximum limit reached (${StreamPlayConcurrency.MAX_PROVIDER_CONCURRENCY} tasks). Races all providers simultaneously."
                else -> "8 to 12 keeps slow connections stable. 16 is balanced for most phones and TVs. 32+ races more providers simultaneously."
            }
        }

        fun applyConcurrency(newValue: Int, sourceView: View) {
            sourceView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            StreamPlayDesignTokens.beginMicroTransition(rootContainer, context)
            currentValue = StreamPlayConcurrency.normalizeConcurrency(newValue)
            saveValue()
            updateUI()
        }

        btnDecrease.setOnClickListener {
            if (currentValue > StreamPlayConcurrency.MIN_PROVIDER_CONCURRENCY) {
                applyConcurrency(currentValue - 4, it)
            }
        }

        btnIncrease.setOnClickListener {
            if (currentValue < StreamPlayConcurrency.MAX_PROVIDER_CONCURRENCY) {
                applyConcurrency(currentValue + 4, it)
            }
        }

        presetLow?.setOnClickListener { applyConcurrency(8, it) }
        presetBalanced?.setOnClickListener { applyConcurrency(16, it) }
        presetFast?.setOnClickListener { applyConcurrency(32, it) }

        btnClose.setOnClickListener {
            dismiss()
        }

        updateUI()
    }

    private fun saveValue() {
        sharedPref.edit {
            putInt("provider_concurrency", currentValue)
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}