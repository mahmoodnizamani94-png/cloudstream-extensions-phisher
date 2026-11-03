package com.phisher98

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.phisher98.settings.SettingsFragment
import com.phisher98.settings.ToggleFragment
import com.phisher98.settings.WyzieSettingsFragment

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
                (displayMetrics.widthPixels * 0.9f).toInt()
            }
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
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
        val subtitleModeLabel: android.widget.TextView = view.findView("subtitle_mode_label")
        val subtitleModeDesc: android.widget.TextView? = runCatching {
            view.findView<android.widget.TextView>("subtitle_mode_desc")
        }.getOrNull()
        val subtitleModeIcon: ImageView = view.findView("subtitle_mode_icon")

        val loginCard: ImageView = view.findView("loginCard")
        val wyzieCard: ImageView = view.findView("wyzieCard")
        val featureCard: ImageView = view.findView("featureCard")
        val toggleproviders: ImageView = view.findView("toggleproviders")
        val languagechange: ImageView = view.findView("languageCard")
        val stremioaddon: ImageView = view.findView("stremioaddons")
        val stremioaddonstreams: ImageView = view.findView("stremioaddonstreams")
        val performance: ImageView = view.findView("performance")

        val loginRow: View = view.findView("loginRow")
        val wyzieRow: View = view.findView("wyzieRow")
        val featureRow: View = view.findView("featureRow")
        val toggleprovidersRow: View = view.findView("toggleprovidersRow")
        val languageRow: View = view.findView("languageRow")
        val stremioaddonsRow: View = view.findView("stremioaddonsRow")
        val stremioaddonstreamsRow: View = view.findView("stremioaddonstreamsRow")
        val performanceRow: View = view.findView("performanceRow")

        val saveIcon: ImageView = view.findView("saveIcon")

        subtitleModeIcon.setImageDrawable(getDrawable("settings_icon"))
        loginCard.setImageDrawable(getDrawable("settings_icon"))
        wyzieCard.setImageDrawable(getDrawable("settings_icon"))
        languagechange.setImageDrawable(getDrawable("settings_icon"))
        featureCard.setImageDrawable(getDrawable("settings_icon"))
        toggleproviders.setImageDrawable(getDrawable("settings_icon"))
        stremioaddon.setImageDrawable(getDrawable("settings_icon"))
        stremioaddonstreams.setImageDrawable(getDrawable("settings_icon"))
        performance.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        subtitleModeRow.background = getDrawable("settings_item_background")
        loginRow.background = getDrawable("settings_item_background")
        wyzieRow.background = getDrawable("settings_item_background")
        featureRow.background = getDrawable("settings_item_background")
        toggleprovidersRow.background = getDrawable("settings_item_background")
        languageRow.background = getDrawable("settings_item_background")
        stremioaddonsRow.background = getDrawable("settings_item_background")
        stremioaddonstreamsRow.background = getDrawable("settings_item_background")
        performanceRow.background = getDrawable("settings_item_background")

        fun refreshSubtitleModeLabel() {
            val currentMode = UrduSubtitleEngine.getSubtitleMode(context, sharedPref)
            if (currentMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                subtitleModeLabel.text =
                    "Subtitle Mode: ${currentMode.displayLabel} • ${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Sources"
                subtitleModeDesc?.text =
                    "Active: ${UrduSubtitleEngine.sotaSourcesSummary()}. Every track is labeled Urdu [Source] in the player."
            } else {
                subtitleModeLabel.text = "Subtitle Mode: ${currentMode.displayLabel} • 7 English Sources"
                subtitleModeDesc?.text =
                    "Tap to switch to Urdu (\u0627\u0631\u062f\u0648) — ${UrduSubtitleEngine.sotaSourcesSummary()} (long-press for instant 1-tap toggle)"
            }
        }
        refreshSubtitleModeLabel()

        fun applySubtitleModeChoice(ctx: android.content.Context, selectedMode: UrduSubtitleEngine.SubtitleMode) {
            UrduSubtitleEngine.setSubtitleMode(selectedMode, ctx, sharedPref)
            refreshSubtitleModeLabel()
            val detail = if (selectedMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                "${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Urdu Sources + Neural AI active immediately"
            } else {
                "Default English subtitles active immediately"
            }
            android.widget.Toast.makeText(
                ctx,
                "Subtitle Mode: ${selectedMode.displayLabel} — $detail",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        subtitleModeRow.setOnLongClickListener { rowView ->
            val ctx = this.context ?: return@setOnLongClickListener false
            rowView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val currentMode = UrduSubtitleEngine.getSubtitleMode(ctx, sharedPref)
            val nextMode = if (currentMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                UrduSubtitleEngine.SubtitleMode.DEFAULT
            } else {
                UrduSubtitleEngine.SubtitleMode.URDU
            }
            applySubtitleModeChoice(ctx, nextMode)
            true
        }

        subtitleModeRow.setOnClickListener { rowView ->
            val ctx = this.context ?: return@setOnClickListener
            rowView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val currentMode = UrduSubtitleEngine.getSubtitleMode(ctx, sharedPref)
            val checkedItem = if (currentMode == UrduSubtitleEngine.SubtitleMode.URDU) 1 else 0
            val options = arrayOf(
                "Default (English — 7 Verified English Sources)",
                "Urdu / \u0627\u0631\u062f\u0648 (${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Sources: SubDL, SubSource, Wyzie, SubSense, YTS-Subs, Subf2m, SubtitleCat, OpenSubtitles + Neural AI)"
            )
            AlertDialog.Builder(ctx)
                .setTitle("Subtitle Mode (Streams & Downloads)")
                .setSingleChoiceItems(options, checkedItem) { dialogInterface, which ->
                    val selectedMode = if (which == 1) {
                        UrduSubtitleEngine.SubtitleMode.URDU
                    } else {
                        UrduSubtitleEngine.SubtitleMode.DEFAULT
                    }
                    applySubtitleModeChoice(ctx, selectedMode)
                    dialogInterface.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

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
