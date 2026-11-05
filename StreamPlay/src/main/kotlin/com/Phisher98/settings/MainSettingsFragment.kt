package com.phisher98

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.phisher98.settings.ToggleFragment
import com.phisher98.settings.WyzieSettingsFragment
import kotlin.math.roundToInt

class MainSettingsFragment(
    private val plugin: StreamPlayPlugin,
    private val sharedPref: android.content.SharedPreferences
) : DialogFragment() {

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val displayMetrics = resources.displayMetrics
            val maxDialogWidth = (500 * displayMetrics.density).toInt()
            val width = if (displayMetrics.widthPixels > 0 && displayMetrics.widthPixels > maxDialogWidth) {
                maxDialogWidth
            } else {
                (displayMetrics.widthPixels * 0.92f).toInt()
            }
            val maxDialogHeight = (displayMetrics.heightPixels * 0.90f).toInt()
            setGravity(Gravity.CENTER)
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.post {
                if (decorView.height > maxDialogHeight && maxDialogHeight > 0) {
                    setLayout(width, maxDialogHeight)
                }
            }
        }
    }

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun buildRoundedBox(
        fillColorHex: String,
        cornerRadiusDp: Float,
        strokeWidthDp: Float = 0f,
        strokeColorHex: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(cornerRadiusDp)
            setColor(Color.parseColor(fillColorHex))
            if (strokeWidthDp > 0f && strokeColorHex != null) {
                setStroke(dpToPx(strokeWidthDp).roundToInt().coerceAtLeast(1), Color.parseColor(strokeColorHex))
            }
        }
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw IllegalStateException("View ID $name not found.")
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (outlineId != 0) {
            this.background = res.getDrawable(outlineId, null)
        }
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = getLayout("fragment_main_settings", inflater, container)
        val drawableId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (drawableId != 0) {
            view.background = res.getDrawable(drawableId, null)
        }

        val subtitleModeRow: View = view.findView("subtitle_mode_button")
        val subtitleModeLabel: TextView = view.findView("subtitle_mode_label")
        val subtitleModeDesc: TextView? = runCatching {
            view.findView<TextView>("subtitle_mode_desc")
        }.getOrNull()
        val subtitleModeIcon: ImageView? = runCatching {
            view.findView<ImageView>("subtitle_mode_icon")
        }.getOrNull()
        val subtitleModeStatusBadge: TextView? = runCatching {
            view.findView<TextView>("subtitle_mode_status_badge")
        }.getOrNull()
        val subtitleModePillTrack: View? = runCatching {
            view.findView<View>("subtitle_mode_pill_track")
        }.getOrNull()
        val subtitleModePillDefault: TextView? = runCatching {
            view.findView<TextView>("subtitle_mode_pill_default")
        }.getOrNull()
        val subtitleModePillUrdu: TextView? = runCatching {
            view.findView<TextView>("subtitle_mode_pill_urdu")
        }.getOrNull()
        val subtitleModeSourcesChips: TextView? = runCatching {
            view.findView<TextView>("subtitle_mode_sources_chips")
        }.getOrNull()

        val loginCard: ImageView? = runCatching { view.findView<ImageView>("loginCard") }.getOrNull()
        val wyzieCard: ImageView? = runCatching { view.findView<ImageView>("wyzieCard") }.getOrNull()
        val featureCard: ImageView? = runCatching { view.findView<ImageView>("featureCard") }.getOrNull()
        val toggleproviders: ImageView? = runCatching { view.findView<ImageView>("toggleproviders") }.getOrNull()
        val languagechange: ImageView? = runCatching { view.findView<ImageView>("languageCard") }.getOrNull()
        val stremioaddon: ImageView? = runCatching { view.findView<ImageView>("stremioaddons") }.getOrNull()
        val stremioaddonstreams: ImageView? = runCatching { view.findView<ImageView>("stremioaddonstreams") }.getOrNull()
        val performance: ImageView? = runCatching { view.findView<ImageView>("performance") }.getOrNull()

        val loginRow: View = view.findView("loginRow")
        val wyzieRow: View = view.findView("wyzieRow")
        val featureRow: View = view.findView("featureRow")
        val toggleprovidersRow: View = view.findView("toggleprovidersRow")
        val languageRow: View = view.findView("languageRow")
        val stremioaddonsRow: View = view.findView("stremioaddonsRow")
        val stremioaddonstreamsRow: View = view.findView("stremioaddonstreamsRow")
        val performanceRow: View = view.findView("performanceRow")

        val saveIcon: ImageView = view.findView("saveIcon")

        runCatching {
            val settingsIcon = getDrawable("settings_icon")
            subtitleModeIcon?.setImageDrawable(settingsIcon)
            loginCard?.setImageDrawable(getDrawable("settings_icon"))
            wyzieCard?.setImageDrawable(getDrawable("settings_icon"))
            languagechange?.setImageDrawable(getDrawable("settings_icon"))
            featureCard?.setImageDrawable(getDrawable("settings_icon"))
            toggleproviders?.setImageDrawable(getDrawable("settings_icon"))
            stremioaddon?.setImageDrawable(getDrawable("settings_icon"))
            stremioaddonstreams?.setImageDrawable(getDrawable("settings_icon"))
            performance?.setImageDrawable(getDrawable("settings_icon"))
            saveIcon.setImageDrawable(getDrawable("save_icon"))
        }

        runCatching {
            loginRow.background = getDrawable("settings_item_background")
            wyzieRow.background = getDrawable("settings_item_background")
            featureRow.background = getDrawable("settings_item_background")
            toggleprovidersRow.background = getDrawable("settings_item_background")
            languageRow.background = getDrawable("settings_item_background")
            stremioaddonsRow.background = getDrawable("settings_item_background")
            stremioaddonstreamsRow.background = getDrawable("settings_item_background")
            performanceRow.background = getDrawable("settings_item_background")
        }

        fun updateSubtitleModeCardUi() {
            val currentMode = UrduSubtitleEngine.getSubtitleMode(context, sharedPref)
            val isUrdu = currentMode == UrduSubtitleEngine.SubtitleMode.URDU

            subtitleModePillTrack?.background = buildRoundedBox(
                fillColorHex = "#0F172A",
                cornerRadiusDp = 14f,
                strokeWidthDp = 1f,
                strokeColorHex = "#1E293B"
            )

            if (isUrdu) {
                subtitleModeRow.background = buildRoundedBox(
                    fillColorHex = "#141E28",
                    cornerRadiusDp = 16f,
                    strokeWidthDp = 1.5f,
                    strokeColorHex = "#10B981"
                )
                subtitleModeLabel.text =
                    "Subtitle Mode: Urdu (\u0627\u0631\u062f\u0648) • ${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Sources"
                subtitleModeDesc?.text =
                    "Active: ${UrduSubtitleEngine.sotaSourcesSummary()}. Every track is labeled Urdu [Source] in the player."
                subtitleModeStatusBadge?.apply {
                    text = "URDU SOTA ACTIVE"
                    setTextColor(Color.parseColor("#34D399"))
                    background = buildRoundedBox("#064E3B", 999f, 1f, "#10B981")
                }
                subtitleModePillUrdu?.apply {
                    background = buildRoundedBox("#059669", 12f)
                    setTextColor(Color.parseColor("#FFFFFF"))
                    isSelected = true
                }
                subtitleModePillDefault?.apply {
                    background = buildRoundedBox("#1E293B", 12f)
                    setTextColor(Color.parseColor("#94A3B8"))
                    isSelected = false
                }
                subtitleModeSourcesChips?.apply {
                    text = "✓ Spoken Urdu Idiom Engine • SubDL • Subf2m • YTS-Subs • OpenSubtitles • SubSource • SubSense • Wyzie • Contextual AI"
                    setTextColor(Color.parseColor("#6EE7B7"))
                }
            } else {
                subtitleModeRow.background = buildRoundedBox(
                    fillColorHex = "#161B26",
                    cornerRadiusDp = 16f,
                    strokeWidthDp = 1.5f,
                    strokeColorHex = "#3B82F6"
                )
                subtitleModeLabel.text = "Subtitle Mode: Default (English) • 7 English Sources"
                subtitleModeDesc?.text =
                    "Tap any pill below for instant 0ms switch to Urdu (\u0627\u0631\u062f\u0648) — ${UrduSubtitleEngine.sotaSourcesSummary()}"
                subtitleModeStatusBadge?.apply {
                    text = "ENGLISH DEFAULT"
                    setTextColor(Color.parseColor("#93C5FD"))
                    background = buildRoundedBox("#1E3A8A", 999f, 1f, "#3B82F6")
                }
                subtitleModePillDefault?.apply {
                    background = buildRoundedBox("#2563EB", 12f)
                    setTextColor(Color.parseColor("#FFFFFF"))
                    isSelected = true
                }
                subtitleModePillUrdu?.apply {
                    background = buildRoundedBox("#1E293B", 12f)
                    setTextColor(Color.parseColor("#94A3B8"))
                    isSelected = false
                }
                subtitleModeSourcesChips?.apply {
                    text = "✓ 7 Verified English Harvesters • 5-Source Download Diversity • Instant 1-Tap Urdu Switch Above"
                    setTextColor(Color.parseColor("#93C5FD"))
                }
            }
        }

        updateSubtitleModeCardUi()

        fun applyModeSelection(ctx: Context, selectedMode: UrduSubtitleEngine.SubtitleMode) {
            UrduSubtitleEngine.setSubtitleMode(
                mode = selectedMode,
                context = ctx,
                sharedPref = sharedPref
            )
            updateSubtitleModeCardUi()
            val detail = if (selectedMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                "${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Urdu Sources + Spoken Urdu AI active immediately"
            } else {
                "Default English subtitles active immediately"
            }
            Toast.makeText(
                ctx,
                "Subtitle Mode: ${selectedMode.displayLabel} — $detail",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun showSubtitleApiKeysDialog(ctx: Context) {
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val pad = dpToPx(18f).roundToInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            val subDlInput = android.widget.EditText(ctx).apply {
                hint = "Optional SubDL API Key (api.subdl.com)"
                setText(UrduSubtitleEngine.resolveSubDlApiKey(ctx, sharedPref).orEmpty())
                setSingleLine()
            }
            val subSourceInput = android.widget.EditText(ctx).apply {
                hint = "Optional SubSource API Key (api.subsource.net)"
                setText(UrduSubtitleEngine.resolveSubSourceApiKey(ctx, sharedPref).orEmpty())
                setSingleLine()
            }
            val wyzieInput = android.widget.EditText(ctx).apply {
                hint = "Optional Wyzie API Key (sub.wyzie.ru)"
                setText(UrduSubtitleEngine.resolveWyzieApiKey(ctx, sharedPref).orEmpty())
                setSingleLine()
            }
            container.addView(subDlInput)
            container.addView(subSourceInput)
            container.addView(wyzieInput)

            AlertDialog.Builder(ctx)
                .setTitle("Optional Subtitle API Keys (Cross-Module)")
                .setMessage("All 8 Urdu sources work out-of-the-box without keys. Adding optional SubDL, SubSource, or Wyzie API keys unlocks direct JSON API tiers across both StreamPlay and SuperStream.")
                .setView(container)
                .setPositiveButton("Save Keys") { _, _ ->
                    val dlKey = subDlInput.text?.toString()?.trim().orEmpty()
                    val ssKey = subSourceInput.text?.toString()?.trim().orEmpty()
                    val wyKey = wyzieInput.text?.toString()?.trim().orEmpty()
                    val prefStores = listOfNotNull(
                        sharedPref,
                        runCatching { ctx.applicationContext.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE) }.getOrNull(),
                        runCatching { ctx.applicationContext.getSharedPreferences("SuperStream", Context.MODE_PRIVATE) }.getOrNull(),
                        runCatching { ctx.applicationContext.getSharedPreferences("cloudstream_subtitle_mode_prefs", Context.MODE_PRIVATE) }.getOrNull()
                    )
                    for (store in prefStores) {
                        store.edit().apply {
                            putString("subdl_api_key", dlKey)
                            putString("subsource_api_key", ssKey)
                            if (wyKey.isNotEmpty()) {
                                putString("wyzie_key", wyKey)
                                putString("wyzie_api_key", wyKey)
                            }
                            apply()
                        }
                    }
                    if (dlKey.isNotEmpty()) System.setProperty("cloudstream.subdl.key", dlKey)
                    if (ssKey.isNotEmpty()) System.setProperty("cloudstream.subsource.key", ssKey)
                    if (wyKey.isNotEmpty()) System.setProperty("cloudstream.wyzie.key", wyKey)
                    Toast.makeText(ctx, "Subtitle API keys saved across StreamPlay & SuperStream", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun showSubtitleModeDialog(ctx: Context) {
            val currentMode = UrduSubtitleEngine.getSubtitleMode(ctx, sharedPref)
            val checkedItem = if (currentMode == UrduSubtitleEngine.SubtitleMode.URDU) 1 else 0
            val options = arrayOf(
                "🇬🇧 Default (English — 7 Verified English Sources)",
                "🇵🇰 Urdu / \u0627\u0631\u062f\u0648 (${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Sources: SubDL, Subf2m, YTS-Subs, OpenSubtitles, SubSource, SubSense, Wyzie, SubtitleCat + Spoken Urdu AI)"
            )
            AlertDialog.Builder(ctx)
                .setTitle("Subtitle Mode (Streams & Downloads)")
                .setSingleChoiceItems(options, checkedItem) { dialogInterface, which ->
                    val selectedMode = if (which == 1) {
                        UrduSubtitleEngine.SubtitleMode.URDU
                    } else {
                        UrduSubtitleEngine.SubtitleMode.DEFAULT
                    }
                    applyModeSelection(ctx, selectedMode)
                    dialogInterface.dismiss()
                }
                .setNeutralButton("API Keys (Optional)") { _, _ ->
                    showSubtitleApiKeysDialog(ctx)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        subtitleModePillDefault?.setOnClickListener { pillView ->
            val ctx = this.context ?: return@setOnClickListener
            pillView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            applyModeSelection(ctx, UrduSubtitleEngine.SubtitleMode.DEFAULT)
        }

        subtitleModePillUrdu?.setOnClickListener { pillView ->
            val ctx = this.context ?: return@setOnClickListener
            pillView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            applyModeSelection(ctx, UrduSubtitleEngine.SubtitleMode.URDU)
        }

        subtitleModeRow.setOnClickListener { rowView ->
            val ctx = this.context ?: return@setOnClickListener
            rowView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val currentMode = UrduSubtitleEngine.getSubtitleMode(ctx, sharedPref)
            val nextMode = if (currentMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                UrduSubtitleEngine.SubtitleMode.DEFAULT
            } else {
                UrduSubtitleEngine.SubtitleMode.URDU
            }
            applyModeSelection(ctx, nextMode)
        }

        subtitleModeRow.setOnLongClickListener { rowView ->
            val ctx = this.context ?: return@setOnLongClickListener false
            rowView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showSubtitleModeDialog(ctx)
            true
        }

        subtitleModeIcon?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { iconView ->
                val ctx = this@MainSettingsFragment.context ?: return@setOnClickListener
                iconView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                showSubtitleModeDialog(ctx)
            }
        }

        saveIcon.makeTvCompatible()
        val showSubFragment = { fragmentCreator: (() -> Unit) -> DialogFragment, tag: String ->
            val fm = activity?.supportFragmentManager
            if (fm != null) {
                dismiss()
                val subFragment = fragmentCreator {
                    val mainSettings = MainSettingsFragment(plugin, sharedPref)
                    mainSettings.show(fm, "main_settings")
                }
                subFragment.show(fm, tag)
            }
        }

        loginRow.visibility = View.GONE

        wyzieRow.setOnClickListener {
            showSubFragment({ cb -> WyzieSettingsFragment(plugin, sharedPref, cb) }, "wyzie_settings_fragment")
        }

        featureRow.setOnClickListener {
            showSubFragment({ cb -> ToggleFragment(plugin, sharedPref, cb) }, "fragment_toggle_extensions")
        }

        toggleprovidersRow.setOnClickListener {
            showSubFragment({ cb -> ProvidersFragment(plugin, sharedPref, cb) }, "fragment_toggle_providers")
        }

        languageRow.setOnClickListener {
            showSubFragment({ cb -> LanguageSelectFragment(plugin, sharedPref, cb) }, "fragment_language_list")
        }

        stremioaddonsRow.setOnClickListener {
            showSubFragment({ cb -> StreamPlayStremioCatelogFrag(plugin, sharedPref, cb) }, "stremio_bottom_sheet_layout")
        }

        stremioaddonstreamsRow.setOnClickListener {
            showSubFragment({ cb -> StreamPlayStremioAddonFrag(plugin, sharedPref, cb) }, "streamplay_stremio_addon_bottom_sheet_layout")
        }

        performanceRow.setOnClickListener {
            showSubFragment({ cb -> ConcurrencyBottomSheet(plugin, sharedPref, cb) }, "concurrency_bottom_sheet")
        }

        saveIcon.setOnClickListener {
            val context = this.context ?: return@setOnClickListener

            AlertDialog.Builder(context)
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No", null)
                .show()
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val basePaddingLeft = view.paddingLeft
        val basePaddingTop = view.paddingTop
        val basePaddingRight = view.paddingRight
        val basePaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
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

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
