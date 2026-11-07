package com.phisher98.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.phisher98.BuildConfig
import com.phisher98.StreamPlayDesignTokens
import com.phisher98.StreamPlayPlugin
import com.phisher98.UrduSubtitleEngine

class WyzieSettingsFragment(
    private var explicitPlugin: StreamPlayPlugin? = null,
    private var explicitSharedPref: SharedPreferences? = null,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    constructor() : this(null, null, null)

    companion object {
        private const val STATE_SHOWING_CLEAR_DIALOG = "state_showing_clear_dialog"
    }

    private var activeSubDialog: Dialog? = null
    private var isShowingClearDialog: Boolean = false

    private val res: Resources
        get() = StreamPlayDesignTokens.resolvePluginResources(this, explicitPlugin)

    private val sharedPref: SharedPreferences
        get() = StreamPlayDesignTokens.resolveSharedPreferences(this, explicitSharedPref)

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
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val id = res.getIdentifier("wyzie_settings_fragment", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
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
        val density = resources.displayMetrics.density
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)

        val wyzieKeyInput = view.findView<EditText>("wyzieKeyInput")
        val subDlKeyInput = view.findViewOrNull<EditText>("subDlKeyInput")
        val subSourceKeyInput = view.findViewOrNull<EditText>("subSourceKeyInput")
        val getKeyButton = view.findView<Button>("getKeyButton")
        val saveButton = view.findView<Button>("saveButton")
        val resetButton = view.findView<Button>("resetButton")

        wyzieKeyInput.background = StreamPlayDesignTokens.inputWellDrawable(density)
        subDlKeyInput?.background = StreamPlayDesignTokens.inputWellDrawable(density)
        subSourceKeyInput?.background = StreamPlayDesignTokens.inputWellDrawable(density)

        StreamPlayDesignTokens.styleButton(getKeyButton, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(saveButton, density, StreamPlayDesignTokens.ButtonVariant.PRIMARY)
        StreamPlayDesignTokens.styleButton(resetButton, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.applyDirectionalFocus(resetButton, saveButton)

        if (savedInstanceState == null) {
            val savedWyzie = UrduSubtitleEngine.resolveWyzieApiKey(context, sharedPref)
            if (!savedWyzie.isNullOrEmpty()) {
                wyzieKeyInput.setText(savedWyzie)
            }
            val savedSubDl = UrduSubtitleEngine.resolveSubDlApiKey(context, sharedPref)
            if (!savedSubDl.isNullOrEmpty()) {
                subDlKeyInput?.setText(savedSubDl)
            }
            val savedSubSource = UrduSubtitleEngine.resolveSubSourceApiKey(context, sharedPref)
            if (!savedSubSource.isNullOrEmpty()) {
                subSourceKeyInput?.setText(savedSubSource)
            }
        }

        fun updateClearButtonState() {
            val hasAnyText = !wyzieKeyInput.text.isNullOrBlank() ||
                !subDlKeyInput?.text.isNullOrBlank() ||
                !subSourceKeyInput?.text.isNullOrBlank()
            resetButton.isEnabled = hasAnyText
        }
        updateClearButtonState()

        wyzieKeyInput.addTextChangedListener { updateClearButtonState() }
        subDlKeyInput?.addTextChangedListener { updateClearButtonState() }
        subSourceKeyInput?.addTextChangedListener { updateClearButtonState() }

        getKeyButton.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://store.wyzie.io/redeem"))
                startActivity(intent)
            } catch (e: Exception) {
                showToast("Unable to open browser: ${e.message}")
            }
        }

        saveButton.setOnClickListener {
            val wyKey = wyzieKeyInput.text?.toString()?.trim().orEmpty()
            val dlKey = subDlKeyInput?.text?.toString()?.trim().orEmpty()
            val ssKey = subSourceKeyInput?.text?.toString()?.trim().orEmpty()

            val prefStores = listOfNotNull(
                sharedPref,
                runCatching { context?.applicationContext?.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE) }.getOrNull(),
                runCatching { context?.applicationContext?.getSharedPreferences("SuperStream", Context.MODE_PRIVATE) }.getOrNull(),
                runCatching { context?.applicationContext?.getSharedPreferences("cloudstream_subtitle_mode_prefs", Context.MODE_PRIVATE) }.getOrNull()
            )
            for (store in prefStores) {
                store.edit().apply {
                    putString("wyzie_key", wyKey)
                    putString("wyzie_api_key", wyKey)
                    putString("subdl_api_key", dlKey)
                    putString("subsource_api_key", ssKey)
                    apply()
                }
            }

            if (wyKey.isNotEmpty()) System.setProperty("cloudstream.wyzie.key", wyKey) else System.clearProperty("cloudstream.wyzie.key")
            if (dlKey.isNotEmpty()) System.setProperty("cloudstream.subdl.key", dlKey) else System.clearProperty("cloudstream.subdl.key")
            if (ssKey.isNotEmpty()) System.setProperty("cloudstream.subsource.key", ssKey) else System.clearProperty("cloudstream.subsource.key")

            showToast("Subtitle API keys saved.")
            dismiss()
        }

        fun showClearKeysConfirmation() {
            val ctx = context ?: return
            if (activeSubDialog?.isShowing == true) return
            isShowingClearDialog = true
            val dlg = StreamPlayDesignTokens.showConfirmationDialog(
                context = ctx,
                resources = resources,
                title = "Clear subtitle API keys?",
                message = "Remove saved Wyzie, SubDL, and SubSource keys from this device?",
                confirmText = "Clear keys",
                cancelText = "Cancel",
                confirmVariant = StreamPlayDesignTokens.ButtonVariant.DANGER
            ) {
                isShowingClearDialog = false
                val prefStores = listOfNotNull(
                    sharedPref,
                    runCatching { ctx.applicationContext?.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE) }.getOrNull(),
                    runCatching { ctx.applicationContext?.getSharedPreferences("SuperStream", Context.MODE_PRIVATE) }.getOrNull(),
                    runCatching { ctx.applicationContext?.getSharedPreferences("cloudstream_subtitle_mode_prefs", Context.MODE_PRIVATE) }.getOrNull()
                )
                for (store in prefStores) {
                    store.edit().apply {
                        remove("wyzie_key")
                        remove("wyzie_api_key")
                        remove("subdl_api_key")
                        remove("subsource_api_key")
                        apply()
                    }
                }
                runCatching {
                    System.clearProperty("cloudstream.wyzie.key")
                    System.clearProperty("cloudstream.subdl.key")
                    System.clearProperty("cloudstream.subsource.key")
                }
                wyzieKeyInput.setText("")
                subDlKeyInput?.setText("")
                subSourceKeyInput?.setText("")
                updateClearButtonState()
                showToast("Subtitle API keys cleared.")
                dismiss()
            }
            dlg.setOnDismissListener {
                if (activeSubDialog === dlg) {
                    activeSubDialog = null
                    isShowingClearDialog = false
                }
            }
            activeSubDialog = dlg
        }

        resetButton.setOnClickListener {
            showClearKeysConfirmation()
        }

        if (savedInstanceState?.getBoolean(STATE_SHOWING_CLEAR_DIALOG, false) == true) {
            showClearKeysConfirmation()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOWING_CLEAR_DIALOG, isShowingClearDialog)
    }

    override fun onDestroyView() {
        activeSubDialog?.setOnDismissListener(null)
        activeSubDialog?.dismiss()
        activeSubDialog = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        StreamPlayDesignTokens.notifyParentSettingsChanged(this, onDismissCallback)
    }
}
