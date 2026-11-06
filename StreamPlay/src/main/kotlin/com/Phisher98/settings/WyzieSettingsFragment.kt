package com.phisher98.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.phisher98.BuildConfig
import com.phisher98.StreamPlayPlugin
import com.lagradost.cloudstream3.CommonActivity.showToast

class WyzieSettingsFragment(
    plugin: StreamPlayPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to read resources")

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
    }

    private fun getDrawable(name: String): android.graphics.drawable.Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val id = res.getIdentifier("wyzie_settings_fragment", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
        val view = inflater.inflate(layout, container, false)
        val drawableId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (drawableId != 0) {
            view.background = res.getDrawable(drawableId, null)
        }
        return view
    }

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

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val cardContainer = view.findView<View>("cardContainer")
        val wyzieKeyInput = view.findView<EditText>("wyzieKeyInput")
        val getKeyButton = view.findView<Button>("getKeyButton")
        val saveButton = view.findView<Button>("saveButton")
        val resetButton = view.findView<Button>("resetButton")

        cardContainer.background = getDrawable("settings_item_background")
        wyzieKeyInput.background = getDrawable("input_text_selector")
        getKeyButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4285F4"))
        getKeyButton.setTextColor(android.graphics.Color.WHITE)
        saveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#6200EE"))
        saveButton.setTextColor(android.graphics.Color.WHITE)
        resetButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D32F2F"))
        resetButton.setTextColor(android.graphics.Color.WHITE)

        val savedKey = sharedPref.getString("wyzie_key", null)?.takeIf { it.isNotBlank() }
            ?: sharedPref.getString("wyzie_api_key", null)?.takeIf { it.isNotBlank() }
            ?: runCatching {
                context?.getSharedPreferences("cloudstream_subtitle_mode_prefs", android.content.Context.MODE_PRIVATE)
                    ?.getString("wyzie_key", null)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { System.getProperty("cloudstream.wyzie.key") }.getOrNull()?.takeIf { it.isNotBlank() }
        if (!savedKey.isNullOrEmpty()) {
            wyzieKeyInput.setText(savedKey)
        }

        getKeyButton.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://store.wyzie.io/redeem"))
                startActivity(intent)
            } catch (e: Exception) {
                showToast("Could not open browser: ${e.message}")
            }
        }

        saveButton.setOnClickListener {
            val key = wyzieKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                sharedPref.edit()?.apply {
                    putString("wyzie_key", key)
                    putString("wyzie_api_key", key)
                    apply()
                }
                runCatching { System.setProperty("cloudstream.wyzie.key", key) }
                context?.let { ctx ->
                    listOf("StreamPlay", "SuperStream", "cloudstream_subtitle_mode_prefs").forEach { prefName ->
                        runCatching {
                            ctx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                                ?.edit()?.apply {
                                    putString("wyzie_key", key)
                                    putString("wyzie_api_key", key)
                                    apply()
                                }
                        }
                    }
                }
                showToast("Wyzie key saved and active immediately across StreamPlay & SuperStream.")
                dismiss()
            } else {
                showToast("Please enter a valid key")
            }
        }

        resetButton.setOnClickListener {
            sharedPref.edit()?.apply {
                remove("wyzie_key")
                remove("wyzie_api_key")
                apply()
            }
            runCatching { System.clearProperty("cloudstream.wyzie.key") }
            context?.let { ctx ->
                listOf("StreamPlay", "SuperStream", "cloudstream_subtitle_mode_prefs").forEach { prefName ->
                    runCatching {
                        ctx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                            ?.edit()?.apply {
                                remove("wyzie_key")
                                remove("wyzie_api_key")
                                apply()
                            }
                    }
                }
            }
            wyzieKeyInput.setText("")
            showToast("Wyzie key reset across StreamPlay & SuperStream.")
            dismiss()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
