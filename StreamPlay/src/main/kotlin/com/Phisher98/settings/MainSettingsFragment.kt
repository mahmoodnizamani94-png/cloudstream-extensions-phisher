package com.phisher98

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.phisher98.settings.ToggleFragment
import com.phisher98.settings.WyzieSettingsFragment
import org.json.JSONArray

class MainSettingsFragment(
    private val plugin: StreamPlayPlugin,
    private val sharedPref: android.content.SharedPreferences
) : DialogFragment() {

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources)
    }

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

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

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun countJsonLinks(prefKey: String): Int {
        val raw = sharedPref.getString(prefKey, null) ?: return 0
        return runCatching { JSONArray(raw).length() }.getOrDefault(0)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = getLayout("fragment_main_settings", inflater, container)
        val density = resources.displayMetrics.density
        view.background = StreamPlayDesignTokens.dialogSheetDrawable(density)

        val mainContainer: ViewGroup? = view.findViewOrNull("main_settings_container")
        val subtitleModeRow: View = view.findView("subtitle_mode_button")
        val subtitleModeLabel: TextView = view.findView("subtitle_mode_label")
        val subtitleModeDesc: TextView? = view.findViewOrNull("subtitle_mode_desc")
        val subtitleModePillTrack: View? = view.findViewOrNull("subtitle_mode_pill_track")
        val subtitleModePillDefault: TextView? = view.findViewOrNull("subtitle_mode_pill_default")
        val subtitleModePillUrdu: TextView? = view.findViewOrNull("subtitle_mode_pill_urdu")
        val subtitleModeSourcesChips: TextView? = view.findViewOrNull("subtitle_mode_sources_chips")

        val wyzieCard: ImageView? = view.findViewOrNull("wyzieCard")
        val featureCard: ImageView? = view.findViewOrNull("featureCard")
        val toggleproviders: ImageView? = view.findViewOrNull("toggleproviders")
        val languagechange: ImageView? = view.findViewOrNull("languageCard")
        val stremioaddon: ImageView? = view.findViewOrNull("stremioaddons")
        val stremioaddonstreams: ImageView? = view.findViewOrNull("stremioaddonstreams")
        val performance: ImageView? = view.findViewOrNull("performance")

        val languageValue: TextView? = view.findViewOrNull("languageValue")
        val wyzieValue: TextView? = view.findViewOrNull("wyzieValue")
        val providersValue: TextView? = view.findViewOrNull("providersValue")
        val performanceValue: TextView? = view.findViewOrNull("performanceValue")
        val featureValue: TextView? = view.findViewOrNull("featureValue")
        val stremioStreamsValue: TextView? = view.findViewOrNull("stremioStreamsValue")
        val stremioCatalogsValue: TextView? = view.findViewOrNull("stremioCatalogsValue")

        val wyzieRow: View = view.findView("wyzieRow")
        val featureRow: View = view.findView("featureRow")
        val toggleprovidersRow: View = view.findView("toggleprovidersRow")
        val languageRow: View = view.findView("languageRow")
        val stremioaddonsRow: View = view.findView("stremioaddonsRow")
        val stremioaddonstreamsRow: View = view.findView("stremioaddonstreamsRow")
        val performanceRow: View = view.findView("performanceRow")

        val saveIcon: ImageView = view.findView("saveIcon")

        runCatching {
            val chevron = runCatching { getDrawable("chevron_right_icon") }.getOrElse { getDrawable("settings_icon") }
            wyzieCard?.setImageDrawable(chevron)
            languagechange?.setImageDrawable(chevron)
            featureCard?.setImageDrawable(chevron)
            toggleproviders?.setImageDrawable(chevron)
            stremioaddon?.setImageDrawable(chevron)
            stremioaddonstreams?.setImageDrawable(chevron)
            performance?.setImageDrawable(chevron)
            saveIcon.setImageDrawable(getDrawable("save_icon"))
        }

        saveIcon.background = StreamPlayDesignTokens.buttonDrawable(
            density,
            StreamPlayDesignTokens.ButtonVariant.ICON_GHOST
        )

        subtitleModeRow.background = StreamPlayDesignTokens.staticCardDrawable(density)
        StreamPlayDesignTokens.applyDirectionalFocus(subtitleModePillDefault, subtitleModePillUrdu)

        listOf(
            wyzieRow,
            featureRow,
            toggleprovidersRow,
            languageRow,
            stremioaddonsRow,
            stremioaddonstreamsRow,
            performanceRow
        ).forEach { row ->
            row.background = StreamPlayDesignTokens.cardSurfaceDrawable(density)
        }

        fun refreshLiveRowSummaries() {
            // 1. Language code
            val langCode = sharedPref.getString("tmdb_language_code", "en-US") ?: "en-US"
            languageValue?.text = langCode

            // 2. Subtitle API keys count
            val keyCount = StreamPlayDesignTokens.countConfiguredSubtitleKeys(context, sharedPref)
            wyzieValue?.apply {
                if (keyCount > 0) {
                    text = "$keyCount active"
                    setTextColor(StreamPlayDesignTokens.ACCENT_SOFT)
                } else {
                    text = "Optional"
                    setTextColor(StreamPlayDesignTokens.TEXT_MUTED)
                }
            }

            // 3. Enabled providers ratio
            val allProviders = buildProviders()
            val disabledSet = getOrInitializeDisabledProviders(sharedPref)
            val enabledCount = allProviders.count { it.id.equals("vidlink", ignoreCase = true) || !disabledSet.contains(it.id) }
            providersValue?.text = "$enabledCount / ${allProviders.size}"

            // 4. Concurrency value + unified tier label ("Stable", "Balanced", "Fast", "Max")
            val concurrency = sharedPref
                .getInt("provider_concurrency", StreamPlayConcurrency.DeviceProfile.MID_RANGE.recommendedConcurrency)
                .let(StreamPlayConcurrency::normalizeConcurrency)
            performanceValue?.text = StreamPlayDesignTokens.formatConcurrencySummary(concurrency)

            // 5. Active extensions count
            val catalogCount = countJsonLinks("streamplay_stremio_saved_links")
            val totalExt = 2 + catalogCount
            val savedPlugins = sharedPref.getStringSet("enabled_plugins_saved", null)
            val activeExt = savedPlugins?.size ?: totalExt
            featureValue?.text = "$activeExt active"

            // 6. Stremio stream addons count
            val streamAddonCount = countJsonLinks(StreamPlayStremioAddonSettings.PREF_KEY_LINKS)
            stremioStreamsValue?.apply {
                if (streamAddonCount > 0) {
                    text = "$streamAddonCount linked"
                    setTextColor(StreamPlayDesignTokens.ACCENT_SOFT)
                } else {
                    text = "None"
                    setTextColor(StreamPlayDesignTokens.TEXT_MUTED)
                }
            }

            // 7. Stremio catalog addons count
            stremioCatalogsValue?.apply {
                if (catalogCount > 0) {
                    text = "$catalogCount linked"
                    setTextColor(StreamPlayDesignTokens.ACCENT_SOFT)
                } else {
                    text = "None"
                    setTextColor(StreamPlayDesignTokens.TEXT_MUTED)
                }
            }
        }

        fun updateSubtitleModeCardUi(animate: Boolean = false) {
            if (animate) {
                StreamPlayDesignTokens.beginMicroTransition(mainContainer, context)
            }
            val currentMode = UrduSubtitleEngine.getSubtitleMode(context, sharedPref)
            val isUrdu = currentMode == UrduSubtitleEngine.SubtitleMode.URDU

            subtitleModePillTrack?.background = StreamPlayDesignTokens.segmentedTrackDrawable(density)
            subtitleModePillDefault?.text = "English"
            subtitleModePillUrdu?.text = "Urdu ${StreamPlayDesignTokens.isolateRtl("\u0627\u0631\u062f\u0648")}"

            if (isUrdu) {
                val readySources = StreamPlayDesignTokens.countReadyUrduSources(context, sharedPref)
                subtitleModeLabel.text = "Subtitles"
                subtitleModeDesc?.text =
                    "$readySources of ${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} sources ready · Streams and downloads"
                subtitleModePillUrdu?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = true)
                    setTextColor(StreamPlayDesignTokens.ON_ACCENT)
                    isSelected = true
                }
                subtitleModePillDefault?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = false)
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                    isSelected = false
                }
            } else {
                val readyEnglish = StreamPlayDesignTokens.countReadyEnglishSources(context, sharedPref)
                subtitleModeLabel.text = "Subtitles"
                subtitleModeDesc?.text = "$readyEnglish of 6 sources ready · Streams and downloads"
                subtitleModePillDefault?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = true)
                    setTextColor(StreamPlayDesignTokens.ON_ACCENT)
                    isSelected = true
                }
                subtitleModePillUrdu?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = false)
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                    isSelected = false
                }
            }

            subtitleModeSourcesChips?.apply {
                text = StreamPlayDesignTokens.formatSubtitleSourcesLine(isUrdu, context, sharedPref)
                setTextColor(StreamPlayDesignTokens.TEXT_MUTED)
            }
        }

        refreshLiveRowSummaries()
        updateSubtitleModeCardUi(animate = false)

        fun applyModeSelection(ctx: Context, selectedMode: UrduSubtitleEngine.SubtitleMode) {
            val previousMode = UrduSubtitleEngine.getSubtitleMode(ctx, sharedPref)
            UrduSubtitleEngine.setSubtitleMode(
                mode = selectedMode,
                context = ctx,
                sharedPref = sharedPref
            )
            updateSubtitleModeCardUi(animate = previousMode != selectedMode)
        }

        val showSubFragment = { fragmentCreator: (() -> Unit) -> DialogFragment, tag: String ->
            val fm = activity?.supportFragmentManager
            if (fm != null) {
                val subFragment = fragmentCreator {
                    if (isAdded) {
                        refreshLiveRowSummaries()
                        updateSubtitleModeCardUi(animate = false)
                    }
                }
                subFragment.show(fm, tag)
            }
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
                .setTitle("Restart CloudStream?")
                .setMessage("Subtitles, source selection, concurrency, and API keys apply immediately. Restart only if you added or toggled Stremio catalog extensions.")
                .setPositiveButton("Restart now") { _, _ ->
                    dismiss()
                    StreamPlayDesignTokens.restartHostApp(context)
                }
                .setNegativeButton("Keep watching", null)
                .show()
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)
    }
}
