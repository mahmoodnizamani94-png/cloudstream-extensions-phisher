package com.phisher98

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.phisher98.settings.ToggleFragment
import com.phisher98.settings.WyzieSettingsFragment
import org.json.JSONArray

class MainSettingsFragment(
    private var explicitPlugin: StreamPlayPlugin? = null,
    private var explicitSharedPref: SharedPreferences? = null
) : DialogFragment() {

    constructor() : this(null, null)

    private val res: Resources
        get() = StreamPlayDesignTokens.resolvePluginResources(this, explicitPlugin)

    private val sharedPref: SharedPreferences
        get() = StreamPlayDesignTokens.resolveSharedPreferences(this, explicitSharedPref)

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw IllegalStateException("Drawable $name not found")
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
        val sectionSubtitlesGroup: View? = view.findViewOrNull("section_subtitles_group")
        val sectionPlaybackGroup: View? = view.findViewOrNull("section_playback_group")
        val sectionAddonsGroup: View? = view.findViewOrNull("section_addons_group")

        val subtitleModeRow: View = view.findView("subtitle_mode_button")
        val subtitleModeLabel: TextView = view.findView("subtitle_mode_label")
        val subtitleModeDesc: TextView? = view.findViewOrNull("subtitle_mode_desc")
        val subtitleModePillTrack: View? = view.findViewOrNull("subtitle_mode_pill_track")
        val subtitleModePillDefault: TextView? = view.findViewOrNull("subtitle_mode_pill_default")
        val subtitleModePillUrdu: TextView? = view.findViewOrNull("subtitle_mode_pill_urdu")

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
            val chevron = getDrawable("chevron_right_icon")
            wyzieCard?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            languagechange?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            featureCard?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            toggleproviders?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            stremioaddon?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            stremioaddonstreams?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            performance?.setImageDrawable(chevron.constantState?.newDrawable()?.mutate() ?: chevron)
            saveIcon.setImageDrawable(getDrawable("save_icon"))
        }

        saveIcon.background = StreamPlayDesignTokens.buttonDrawable(
            density,
            StreamPlayDesignTokens.ButtonVariant.ICON_GHOST
        )

        // Apply unified OS grouped inset section containers + position-aware row drawables
        sectionSubtitlesGroup?.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        sectionPlaybackGroup?.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        sectionAddonsGroup?.background = StreamPlayDesignTokens.groupedSectionDrawable(density)

        subtitleModeRow.background = null
        StreamPlayDesignTokens.applyDirectionalFocus(subtitleModePillDefault, subtitleModePillUrdu)

        languageRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.MIDDLE)
        wyzieRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.BOTTOM)

        toggleprovidersRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.TOP)
        performanceRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.BOTTOM)

        featureRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.TOP)
        stremioaddonstreamsRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.MIDDLE)
        stremioaddonsRow.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.BOTTOM)

        fun refreshLiveRowSummaries() {
            // 1. Language code
            val langCode = sharedPref.getString("tmdb_language_code", "en-US") ?: "en-US"
            languageValue?.apply {
                text = langCode
                setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
            }

            // 2. Subtitle API keys count
            val keyCount = StreamPlayDesignTokens.countConfiguredSubtitleKeys(context, sharedPref)
            wyzieValue?.apply {
                if (keyCount > 0) {
                    text = "$keyCount of 3"
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                } else {
                    text = "None"
                    setTextColor(StreamPlayDesignTokens.TEXT_MUTED)
                }
            }

            // 3. Enabled providers ratio
            val allProviders = buildProviders()
            val disabledSet = getOrInitializeDisabledProviders(sharedPref)
            val enabledCount = allProviders.count { it.id.equals("vidlink", ignoreCase = true) || !disabledSet.contains(it.id) }
            providersValue?.apply {
                text = "$enabledCount of ${allProviders.size}"
                setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
            }

            // 4. Concurrency value + unified tier label ("Stable", "Balanced", "Fast", "Max")
            val concurrency = sharedPref
                .getInt("provider_concurrency", StreamPlayConcurrency.DeviceProfile.MID_RANGE.recommendedConcurrency)
                .let(StreamPlayConcurrency::normalizeConcurrency)
            performanceValue?.apply {
                text = StreamPlayDesignTokens.formatConcurrencySummary(concurrency)
                setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
            }

            // 5. Active extensions count
            val catalogCount = countJsonLinks("streamplay_stremio_saved_links")
            val totalExt = 2 + catalogCount
            val savedPlugins = sharedPref.getStringSet("enabled_plugins_saved", null)
            val activeExt = savedPlugins?.size ?: totalExt
            featureValue?.apply {
                text = "$activeExt of $totalExt"
                setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
            }

            // 6. Stremio stream addons count
            val streamAddonCount = countJsonLinks(StreamPlayStremioAddonSettings.PREF_KEY_LINKS)
            stremioStreamsValue?.apply {
                if (streamAddonCount > 0) {
                    text = "$streamAddonCount"
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                } else {
                    text = "None"
                    setTextColor(StreamPlayDesignTokens.TEXT_MUTED)
                }
            }

            // 7. Stremio catalog addons count
            stremioCatalogsValue?.apply {
                if (catalogCount > 0) {
                    text = "$catalogCount"
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
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
                subtitleModeDesc?.text = "$readySources of ${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} sources"
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
                subtitleModeDesc?.text = "$readyEnglish of 6 sources"
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
        }

        refreshLiveRowSummaries()
        updateSubtitleModeCardUi(animate = false)

        parentFragmentManager.setFragmentResultListener(
            StreamPlayDesignTokens.FRAGMENT_RESULT_SETTINGS_CHANGED,
            viewLifecycleOwner
        ) { _, _ ->
            dialog?.window?.decorView?.visibility = View.VISIBLE
            refreshLiveRowSummaries()
            updateSubtitleModeCardUi(animate = false)
        }

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
            if (fm != null && !fm.isStateSaved && fm.findFragmentByTag(tag) == null) {
                dialog?.window?.decorView?.visibility = View.INVISIBLE
                val subFragment = fragmentCreator {
                    if (isAdded) {
                        dialog?.window?.decorView?.visibility = View.VISIBLE
                        refreshLiveRowSummaries()
                        updateSubtitleModeCardUi(animate = false)
                    }
                }
                subFragment.show(fm, tag)
            }
        }

        val pluginRef = explicitPlugin ?: StreamPlayPlugin.instance

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
            showSubFragment({ cb -> WyzieSettingsFragment(pluginRef, sharedPref, cb) }, "wyzie_settings_fragment")
        }

        featureRow.setOnClickListener {
            showSubFragment({ cb -> ToggleFragment(pluginRef, sharedPref, cb) }, "fragment_toggle_extensions")
        }

        toggleprovidersRow.setOnClickListener {
            showSubFragment({ cb -> ProvidersFragment(pluginRef, sharedPref, cb) }, "fragment_toggle_providers")
        }

        languageRow.setOnClickListener {
            showSubFragment({ cb -> LanguageSelectFragment(pluginRef, sharedPref, cb) }, "fragment_language_list")
        }

        stremioaddonsRow.setOnClickListener {
            showSubFragment({ cb -> StreamPlayStremioCatelogFrag(pluginRef, sharedPref, cb) }, "stremio_bottom_sheet_layout")
        }

        stremioaddonstreamsRow.setOnClickListener {
            showSubFragment({ cb -> StreamPlayStremioAddonFrag(pluginRef, sharedPref, cb) }, "streamplay_stremio_addon_bottom_sheet_layout")
        }

        performanceRow.setOnClickListener {
            showSubFragment({ cb -> ConcurrencyBottomSheet(pluginRef, sharedPref, cb) }, "concurrency_bottom_sheet")
        }

        saveIcon.setOnClickListener {
            val ctx = this.context ?: return@setOnClickListener
            StreamPlayDesignTokens.showConfirmationDialog(
                context = ctx,
                resources = resources,
                title = "Restart CloudStream?",
                message = "Subtitles, source selection, concurrency, and API keys apply immediately. Restart only if you added or toggled Stremio catalog extensions.",
                confirmText = "Restart now",
                cancelText = "Keep watching",
                confirmVariant = StreamPlayDesignTokens.ButtonVariant.PRIMARY
            ) {
                dismiss()
                StreamPlayDesignTokens.restartHostApp(ctx)
            }
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        dialog?.window?.decorView?.visibility = View.VISIBLE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)
    }
}
