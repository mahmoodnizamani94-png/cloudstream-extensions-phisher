package com.phisher98.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.phisher98.BuildConfig
import com.phisher98.MODE_DEFAULT
import com.phisher98.MODE_URDU
import com.phisher98.SuperStreamPlugin
import com.phisher98.isUrduSubtitleMode
import com.phisher98.setSubtitleMode
import kotlin.math.roundToInt

class SettingsFragment(
    plugin: SuperStreamPlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to read resources")

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

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw IllegalStateException("View ID $name not found in ${BuildConfig.LIBRARY_PACKAGE_NAME}")
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
            isDraggable = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.M)
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

        val subtitleModeRow = view.findView<View>("subtitle_mode_button")
        val subtitleModeLabel = view.findView<TextView>("subtitle_mode_label")
        val subtitleModeDesc = runCatching { view.findView<TextView>("subtitle_mode_desc") }.getOrNull()
        val subtitleModeStatusBadge = runCatching { view.findView<TextView>("subtitle_mode_status_badge") }.getOrNull()
        val subtitleModePillTrack = runCatching { view.findView<View>("subtitle_mode_pill_track") }.getOrNull()
        val subtitleModePillDefault = runCatching { view.findView<TextView>("subtitle_mode_pill_default") }.getOrNull()
        val subtitleModePillUrdu = runCatching { view.findView<TextView>("subtitle_mode_pill_urdu") }.getOrNull()
        val subtitleModeSourcesChips = runCatching { view.findView<TextView>("subtitle_mode_sources_chips") }.getOrNull()

        val tokenInput = view.findView<EditText>("tokenInput")
        val addButton = view.findView<Button>("addButton")
        val resetButton = view.findView<Button>("resetButton")
        val loginButton = view.findView<Button>("loginButton")
        val webView = view.findView<WebView>("authWebView")

        fun updateSubtitleModeCardUi() {
            val urduActive = isUrduSubtitleMode(sharedPref, context)

            subtitleModePillTrack?.background = buildRoundedBox(
                fillColorHex = "#0F172A",
                cornerRadiusDp = 14f,
                strokeWidthDp = 1f,
                strokeColorHex = "#1E293B"
            )

            if (urduActive) {
                subtitleModeRow.background = buildRoundedBox(
                    fillColorHex = "#141E28",
                    cornerRadiusDp = 16f,
                    strokeWidthDp = 1.5f,
                    strokeColorHex = "#10B981"
                )
                subtitleModeLabel.text = "Subtitle Mode: Urdu (\u0627\u0631\u062f\u0648) • 8 Verified Urdu Sources + Spoken Urdu AI"
                subtitleModeDesc?.text =
                    "Active: 8 Verified Urdu Sources + Spoken Urdu AI (SubDL • Subf2m • YTS-Subs • OpenSubtitles • SubSource • SubSense • Wyzie • SubtitleCat • Contextual AI)"
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
                    text = "✓ Spoken Urdu Idiom Engine • SubDL • Subf2m • YTS-Subs • OpenSubtitles • SubSource • SubSense • Wyzie • SubtitleCat • Contextual AI"
                    setTextColor(Color.parseColor("#6EE7B7"))
                }
            } else {
                subtitleModeRow.background = buildRoundedBox(
                    fillColorHex = "#161B26",
                    cornerRadiusDp = 16f,
                    strokeWidthDp = 1.5f,
                    strokeColorHex = "#3B82F6"
                )
                subtitleModeLabel.text = "Subtitle Mode: Default (English)"
                subtitleModeDesc?.text =
                    "Active: English Subtitles • Tap any pill below for instant 1-tap switch to 8 Verified Urdu Sources + Spoken Urdu AI"
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
                    text = "✓ Verified English Harvesters • Instant 1-Tap Switch to 8 Verified Urdu Sources + Spoken Urdu AI Above"
                    setTextColor(Color.parseColor("#93C5FD"))
                }
            }
        }

        updateSubtitleModeCardUi()

        fun applyModeSelection(ctx: Context, selectedMode: String) {
            setSubtitleMode(selectedMode, ctx, sharedPref)
            updateSubtitleModeCardUi()
            val displayLabel = if (selectedMode == MODE_URDU) {
                "Urdu (\u0627\u0631\u062f\u0648) • 8 Verified Urdu Sources + Spoken Urdu AI"
            } else {
                "Default (English)"
            }
            Toast.makeText(
                ctx,
                "Subtitle Mode: $displayLabel",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun showSubtitleApiKeysDialog(ctx: Context) {
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val pad = dpToPx(18f).roundToInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            val subDlInput = EditText(ctx).apply {
                hint = "Optional SubDL API Key (api.subdl.com)"
                setText(sharedPref.getString("subdl_api_key", null).orEmpty())
                setSingleLine()
            }
            val subSourceInput = EditText(ctx).apply {
                hint = "Optional SubSource API Key (api.subsource.net)"
                setText(sharedPref.getString("subsource_api_key", null).orEmpty())
                setSingleLine()
            }
            val wyzieInput = EditText(ctx).apply {
                hint = "Optional Wyzie API Key (sub.wyzie.ru)"
                setText((sharedPref.getString("wyzie_key", null) ?: sharedPref.getString("wyzie_api_key", null)).orEmpty())
                setSingleLine()
            }
            container.addView(subDlInput)
            container.addView(subSourceInput)
            container.addView(wyzieInput)

            AlertDialog.Builder(ctx)
                .setTitle("Optional Subtitle API Keys (Cross-Module)")
                .setMessage("6 of 8 Urdu sources (SubDL, SubSense, Subf2m, YTS-Subs, OpenSubtitles, SubtitleCat) work keylessly out-of-the-box. Adding SubSource or Wyzie API keys activates those 2 key-gated sources, and an optional SubDL key unlocks its official API v1 JSON tier across both SuperStream and StreamPlay.")
                .setView(container)
                .setPositiveButton("Save Keys") { _, _ ->
                    val dlKey = subDlInput.text?.toString()?.trim().orEmpty()
                    val ssKey = subSourceInput.text?.toString()?.trim().orEmpty()
                    val wyKey = wyzieInput.text?.toString()?.trim().orEmpty()
                    val prefStores = listOfNotNull(
                        sharedPref,
                        runCatching { ctx.applicationContext.getSharedPreferences("SuperStream", Context.MODE_PRIVATE) }.getOrNull(),
                        runCatching { ctx.applicationContext.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE) }.getOrNull(),
                        runCatching { ctx.applicationContext.getSharedPreferences("cloudstream_subtitle_mode_prefs", Context.MODE_PRIVATE) }.getOrNull()
                    )
                    for (store in prefStores) {
                        store.edit {
                            putString("subdl_api_key", dlKey)
                            putString("subsource_api_key", ssKey)
                            putString("wyzie_key", wyKey)
                            putString("wyzie_api_key", wyKey)
                        }
                    }
                    if (dlKey.isNotEmpty()) System.setProperty("cloudstream.subdl.key", dlKey) else System.clearProperty("cloudstream.subdl.key")
                    if (ssKey.isNotEmpty()) System.setProperty("cloudstream.subsource.key", ssKey) else System.clearProperty("cloudstream.subsource.key")
                    if (wyKey.isNotEmpty()) System.setProperty("cloudstream.wyzie.key", wyKey) else System.clearProperty("cloudstream.wyzie.key")
                    Toast.makeText(ctx, "Subtitle API keys saved across SuperStream & StreamPlay", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun showSubtitleModeDialog(ctx: Context) {
            val urduActive = isUrduSubtitleMode(sharedPref, ctx)
            val checkedItem = if (urduActive) 1 else 0
            val options = arrayOf(
                "🇬🇧 Default — English Subtitles (Streams & Downloads)",
                "🇵🇰 Urdu / \u0627\u0631\u062f\u0648 — 8 Verified Urdu Sources + Spoken Urdu AI (SubDL • Subf2m • YTS-Subs • OpenSubtitles • SubSource • SubSense • Wyzie • SubtitleCat • Contextual AI)"
            )
            AlertDialog.Builder(ctx)
                .setTitle("Subtitle Mode • 8 Verified Urdu Sources + Spoken Urdu AI")
                .setSingleChoiceItems(options, checkedItem) { dialogInterface, which ->
                    val selectedMode = if (which == 1) MODE_URDU else MODE_DEFAULT
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
            pillView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val ctx = this.context ?: return@setOnClickListener
            applyModeSelection(ctx, MODE_DEFAULT)
        }

        subtitleModePillUrdu?.setOnClickListener { pillView ->
            pillView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val ctx = this.context ?: return@setOnClickListener
            applyModeSelection(ctx, MODE_URDU)
        }

        subtitleModeRow.setOnClickListener { clickedView ->
            clickedView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val ctx = this.context ?: return@setOnClickListener
            val urduActive = isUrduSubtitleMode(sharedPref, ctx)
            val nextMode = if (urduActive) MODE_DEFAULT else MODE_URDU
            applyModeSelection(ctx, nextMode)
        }

        subtitleModeRow.setOnLongClickListener { clickedView ->
            clickedView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val ctx = this.context ?: return@setOnLongClickListener false
            showSubtitleModeDialog(ctx)
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
                        val rootView = this@SettingsFragment.view ?: return@runOnUiThread
                        val tokenInput = runCatching {
                            rootView.findView<EditText>("tokenInput")
                        }.getOrNull()
                        tokenInput?.setText(finalToken)

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
