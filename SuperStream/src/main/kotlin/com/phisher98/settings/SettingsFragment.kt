package com.phisher98.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phisher98.BuildConfig
import com.phisher98.MODE_DEFAULT
import com.phisher98.MODE_URDU
import com.phisher98.isUrduSubtitleMode
import com.phisher98.setSubtitleMode
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.phisher98.SuperStreamPlugin
import androidx.core.content.edit

class SettingsFragment(
    plugin: SuperStreamPlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to read resources")

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val id = res.getIdentifier("settings_fragment", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false // optional: prevent dragging at all
        }
    }


    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val subtitleModeRow = view.findView<View>("subtitle_mode_button")
        val subtitleModeLabel = view.findView<android.widget.TextView>("subtitle_mode_label")
        val subtitleModeDesc = runCatching { view.findView<android.widget.TextView>("subtitle_mode_desc") }.getOrNull()
        val tokenInput = view.findView<EditText>("tokenInput")
        val addButton = view.findView<Button>("addButton")
        val resetButton = view.findView<Button>("resetButton")
        val loginButton = view.findView<Button>("loginButton")
        val webView = view.findView<WebView>("authWebView")

        fun refreshSubtitleModeLabel() {
            val urduActive = isUrduSubtitleMode(sharedPref, context)
            if (urduActive) {
                subtitleModeLabel.text = "Subtitle Mode: Urdu (اردو) • 4 SOTA Sources + Neural AI"
                subtitleModeDesc?.text = "Active Urdu Sources (4 + AI): SubDL, Wyzie, SubSense, OpenSubtitles + Neural AI • Tap to configure or long-press for 1-tap switch"
            } else {
                subtitleModeLabel.text = "Subtitle Mode: Default (English)"
                subtitleModeDesc?.text = "Active: English Subtitles (OpenSubtitles, Wyzie) • Tap to switch to Urdu (4 SOTA Sources + AI) or long-press for 1-tap toggle"
            }
        }
        refreshSubtitleModeLabel()

        subtitleModeRow.setOnClickListener { clickedView ->
            clickedView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val ctx = this.context ?: return@setOnClickListener
            val urduActive = isUrduSubtitleMode(sharedPref, ctx)
            val checkedItem = if (urduActive) 1 else 0
            val options = arrayOf(
                "Default — English Subtitles (Streams & Downloads)",
                "Urdu / اردو — 4 SOTA Sources + Neural AI (SubDL, Wyzie, SubSense, OpenSubtitles)"
            )
            AlertDialog.Builder(ctx)
                .setTitle("Subtitle Mode • 4 Urdu SOTA Sources + Neural AI")
                .setSingleChoiceItems(options, checkedItem) { dialogInterface, which ->
                    val selectedMode = if (which == 1) MODE_URDU else MODE_DEFAULT
                    val displayLabel = if (which == 1) {
                        "Urdu (اردو) • 4 SOTA Sources + Neural AI"
                    } else {
                        "Default (English)"
                    }
                    setSubtitleMode(selectedMode, ctx, sharedPref)
                    refreshSubtitleModeLabel()
                    dialogInterface.dismiss()
                    android.widget.Toast.makeText(
                        ctx,
                        "Subtitle Mode: $displayLabel",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        subtitleModeRow.setOnLongClickListener { clickedView ->
            clickedView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val ctx = this.context ?: return@setOnLongClickListener false
            val urduActive = isUrduSubtitleMode(sharedPref, ctx)
            val nextMode = if (urduActive) MODE_DEFAULT else MODE_URDU
            val displayLabel = if (!urduActive) {
                "Urdu (اردو) • 4 SOTA Sources + Neural AI"
            } else {
                "Default (English)"
            }
            setSubtitleMode(nextMode, ctx, sharedPref)
            refreshSubtitleModeLabel()
            android.widget.Toast.makeText(
                ctx,
                "Subtitle Mode: $displayLabel",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }

        val savedToken = sharedPref.getString("token", null)
        if (!savedToken.isNullOrEmpty()) {
            tokenInput.setText(savedToken)
        }

        setupWebView(webView)

        loginButton.setOnClickListener {
            webView.visibility = View.VISIBLE
            webView.loadUrl("https://www.febbox.com/login/google?jump=%2F")
        }

        addButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isNotEmpty()) {
                val finalToken = if (token.startsWith("ui=")) token else "ui=$token"

                sharedPref.edit { putString("token", finalToken) }

                val ctx = context ?: run {
                    showToast("Error: Context is null")
                    return@setOnClickListener
                }

                AlertDialog.Builder(ctx)
                    .setTitle("Save & Reload")
                    .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                    .setPositiveButton("Yes") { _, _ ->
                        dismiss()
                        restartApp()
                    }
                    .setNegativeButton("No") { _, _ ->
                        dismiss()
                    }
                    .show()
            } else {
                showToast("Please enter a valid token")
            }
        }

        resetButton.setOnClickListener {
            sharedPref.edit()?.apply {
                remove("token")
                apply()
            }
            tokenInput.setText("ui=")
            showToast("Token reset successfully. Restart the app.")
            dismiss()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.90 Mobile Safari/537.36"

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Resize WebView to content height
                view?.evaluateJavascript(
                    "(function() { return document.body.scrollHeight; })();"
                ) { value ->
                    val height = value.replace("\"", "").toFloatOrNull()
                    if (height != null) {
                        val density = resources.displayMetrics.density
                        val layoutParams = view.layoutParams
                        layoutParams.height = (height * density).toInt()
                        view.layoutParams = layoutParams
                    }
                }

                // Existing token scraping logic
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie(url ?: "")

                val token = cookies?.split(";")
                    ?.map { it.trim() }
                    ?.find { it.startsWith("ui=") }
                    ?.removePrefix("ui=")

                if (!token.isNullOrEmpty() && view != null) {
                    val finalToken = "ui=$token"

                    activity?.runOnUiThread {
                        val tokenInput = requireView().findViewById<EditText>(
                            res.getIdentifier("tokenInput", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
                        )
                        tokenInput.setText(finalToken)

                        sharedPref.edit()?.apply {
                            putString("token", finalToken)
                            apply()
                        }

                        showToast("Login successful!")
                        webView.visibility = View.GONE
                    }
                }
            }
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
