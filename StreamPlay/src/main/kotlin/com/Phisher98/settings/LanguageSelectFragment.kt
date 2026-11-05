package com.phisher98

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class LanguageSelectFragment(
    plugin: StreamPlayPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
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
            val height = (displayMetrics.heightPixels * 0.85f).toInt().coerceAtLeast(1)
            setGravity(Gravity.CENTER)
            setLayout(width, height)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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

    @SuppressLint("UseCompatLoadingForDrawables")
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

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw IllegalStateException("View ID $name not found.")
        return this.findViewById(id)
    }

    private val pinnedPriorityLanguages = listOf(
        "🇵🇰 Urdu — Pakistan (ur-PK)" to "ur-PK",
        "🇮🇳 Urdu — India (ur-IN)" to "ur-IN",
        "🇺🇸 English — United States (en-US)" to "en-US",
        "🇬🇧 English — United Kingdom (en-GB)" to "en-GB"
    )

    // Alphabetical Catalog Language Display List
    private val alphabeticalLanguages = listOf(
        "South Africa (Afrikaans)" to "af-ZA",
        "United Arab Emirates (Arabic)" to "ar-AE",
        "Saudi Arabia (Arabic)" to "ar-SA",
        "Azerbaijan (Azerbaijani)" to "az-AZ",
        "Bulgaria (Bulgarian)" to "bg-BG",
        "India (Bengali)" to "bn-IN",
        "Spain (Catalan)" to "ca-ES",
        "Czech Republic (Czech)" to "cs-CZ",
        "United Kingdom (Welsh)" to "cy-GB",
        "Denmark (Danish)" to "da-DK",
        "Germany (German)" to "de-DE",
        "Greece (Greek)" to "el-GR",
        "Spain (Spanish)" to "es-ES",
        "Latin America (Spanish)" to "es-419",
        "Estonia (Estonian)" to "et-EE",
        "Spain (Basque)" to "eu-ES",
        "Iran (Persian)" to "fa-IR",
        "Finland (Finnish)" to "fi-FI",
        "Philippines (Filipino)" to "fil-PH",
        "France (French)" to "fr-FR",
        "Spain (Galician)" to "gl-ES",
        "India (Gujarati)" to "gu-IN",
        "Israel (Hebrew)" to "he-IL",
        "India (Hindi)" to "hi-IN",
        "Croatia (Croatian)" to "hr-HR",
        "Hungary (Hungarian)" to "hu-HU",
        "Indonesia (Indonesian)" to "id-ID",
        "Iceland (Icelandic)" to "is-IS",
        "Italy (Italian)" to "it-IT",
        "Japan (Japanese)" to "ja-JP",
        "India (Kannada)" to "kn-IN",
        "South Korea (Korean)" to "ko-KR",
        "Lithuania (Lithuanian)" to "lt-LT",
        "Latvia (Latvian)" to "lv-LV",
        "India (Malayalam)" to "ml-IN",
        "Malaysia (Malay)" to "ms-MY",
        "Norway (Norwegian)" to "no-NO",
        "Netherlands (Dutch)" to "nl-NL",
        "Poland (Polish)" to "pl-PL",
        "Brazil (Portuguese)" to "pt-BR",
        "Portugal (Portuguese)" to "pt-PT",
        "Romania (Romanian)" to "ro-RO",
        "Russia (Russian)" to "ru-RU",
        "Slovakia (Slovak)" to "sk-SK",
        "Slovenia (Slovenian)" to "sl-SI",
        "Serbia (Serbian)" to "sr-RS",
        "Sweden (Swedish)" to "sv-SE",
        "India (Tamil)" to "ta-IN",
        "India (Telugu)" to "te-IN",
        "Thailand (Thai)" to "th-TH",
        "Turkey (Turkish)" to "tr-TR",
        "Ukraine (Ukrainian)" to "uk-UA",
        "Vietnam (Vietnamese)" to "vi-VN",
        "China (Chinese Simplified)" to "zh-CN",
        "Taiwan (Chinese Traditional)" to "zh-TW"
    ).sortedBy { it.first.lowercase() }

    private val languageList: List<Pair<String, String>> =
        pinnedPriorityLanguages + alphabeticalLanguages

    private lateinit var adapter: LanguageAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = getLayout("fragment_language_select", inflater, container)
        val drawableId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (drawableId != 0) {
            root.background = res.getDrawable(drawableId, null)
        }

        val banner: View? = runCatching { root.findView<View>("lang_subtitle_mode_banner") }.getOrNull()
        val bannerStatus: TextView? = runCatching { root.findView<TextView>("lang_subtitle_mode_status") }.getOrNull()
        val pillTrack: View? = runCatching { root.findView<View>("lang_pill_track") }.getOrNull()
        val pillDefault: TextView? = runCatching { root.findView<TextView>("lang_pill_default") }.getOrNull()
        val pillUrdu: TextView? = runCatching { root.findView<TextView>("lang_pill_urdu") }.getOrNull()

        val recycler: RecyclerView = root.findView("languageRecycler")
        val search: EditText = root.findView("searchLanguage")
        recycler.makeTvCompatible()

        // Keep search bar clearly visible on Pixel touch phones (do NOT overwrite with transparent outline.xml)
        val inputSelectorId = res.getIdentifier("input_text_selector", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (inputSelectorId != 0) {
            search.background = res.getDrawable(inputSelectorId, null)
        } else {
            search.background = buildRoundedBox("#262626", 10f, 1f, "#475569")
        }

        fun updateSubtitleBannerUi() {
            val currentMode = UrduSubtitleEngine.getSubtitleMode(context, sharedPref)
            val isUrdu = currentMode == UrduSubtitleEngine.SubtitleMode.URDU

            pillTrack?.background = buildRoundedBox("#0F172A", 12f, 1f, "#1E293B")

            if (isUrdu) {
                banner?.background = buildRoundedBox("#141E28", 14f, 1.5f, "#10B981")
                bannerStatus?.text =
                    "Active: Urdu (\u0627\u0631\u062f\u0648) • ${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Sources + Spoken Urdu AI"
                bannerStatus?.setTextColor(Color.parseColor("#6EE7B7"))
                pillUrdu?.apply {
                    background = buildRoundedBox("#059669", 10f)
                    setTextColor(Color.parseColor("#FFFFFF"))
                    isSelected = true
                }
                pillDefault?.apply {
                    background = buildRoundedBox("#1E293B", 10f)
                    setTextColor(Color.parseColor("#94A3B8"))
                    isSelected = false
                }
            } else {
                banner?.background = buildRoundedBox("#161B26", 14f, 1.5f, "#3B82F6")
                bannerStatus?.text =
                    "Active: Default (English) • Tap Urdu (\u0627\u0631\u062f\u0648) below for instant 1-tap switch"
                bannerStatus?.setTextColor(Color.parseColor("#93C5FD"))
                pillDefault?.apply {
                    background = buildRoundedBox("#2563EB", 10f)
                    setTextColor(Color.parseColor("#FFFFFF"))
                    isSelected = true
                }
                pillUrdu?.apply {
                    background = buildRoundedBox("#1E293B", 10f)
                    setTextColor(Color.parseColor("#94A3B8"))
                    isSelected = false
                }
            }
        }

        updateSubtitleBannerUi()

        fun applySubtitleModeFromBanner(selectedMode: UrduSubtitleEngine.SubtitleMode) {
            val ctx = context ?: return
            UrduSubtitleEngine.setSubtitleMode(
                mode = selectedMode,
                context = ctx,
                sharedPref = sharedPref
            )
            updateSubtitleBannerUi()
            val detail = if (selectedMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                "${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} SOTA Urdu Sources + Spoken Urdu AI active"
            } else {
                "Default English subtitles active"
            }
            Toast.makeText(
                ctx,
                "Subtitle Mode: ${selectedMode.displayLabel} — $detail",
                Toast.LENGTH_SHORT
            ).show()
        }

        pillDefault?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            applySubtitleModeFromBanner(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        }

        pillUrdu?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            applySubtitleModeFromBanner(UrduSubtitleEngine.SubtitleMode.URDU)
        }

        banner?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val currentMode = UrduSubtitleEngine.getSubtitleMode(context, sharedPref)
            val nextMode = if (currentMode == UrduSubtitleEngine.SubtitleMode.URDU) {
                UrduSubtitleEngine.SubtitleMode.DEFAULT
            } else {
                UrduSubtitleEngine.SubtitleMode.URDU
            }
            applySubtitleModeFromBanner(nextMode)
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())

        val savedCode = sharedPref.getString("tmdb_language_code", "en-US") ?: "en-US"

        adapter = LanguageAdapter(
            languageList,
            savedCode
        ) { code ->
            sharedPref.edit { putString("tmdb_language_code", code) }
            val ctx = requireContext()
            if (code.startsWith("ur", ignoreCase = true)) {
                UrduSubtitleEngine.setSubtitleMode(
                    mode = UrduSubtitleEngine.SubtitleMode.URDU,
                    context = ctx,
                    sharedPref = sharedPref
                )
                updateSubtitleBannerUi()
                Toast.makeText(
                    ctx,
                    "Language set to $code • Urdu (\u0627\u0631\u062f\u0648) SOTA Subtitles enabled",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (code.startsWith("en", ignoreCase = true)) {
                UrduSubtitleEngine.setSubtitleMode(
                    mode = UrduSubtitleEngine.SubtitleMode.DEFAULT,
                    context = ctx,
                    sharedPref = sharedPref
                )
                updateSubtitleBannerUi()
                Toast.makeText(
                    ctx,
                    "Language set to $code • Default (English) Subtitles enabled",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(ctx, "Language set to $code", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }

        recycler.adapter = adapter

        search.addTextChangedListener { text ->
            adapter.filter(text.toString())
        }

        return root
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

    // ---------------------------------------------------------------------- ADAPTER ------------------ //

    inner class LanguageAdapter(
        private val originalList: List<Pair<String, String>>,
        private val selectedCode: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.VH>() {

        private var filteredList = originalList.toMutableList()

        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findView("radio_language")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = getLayout("item_language", LayoutInflater.from(parent.context), parent)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, code) = filteredList[position]

            holder.radio.text = name
            holder.radio.isChecked = code == selectedCode

            holder.radio.setOnClickListener {
                onClick(code)
            }
        }

        override fun getItemCount() = filteredList.size

        @SuppressLint("NotifyDataSetChanged")
        fun filter(query: String) {
            filteredList = if (query.isBlank()) {
                originalList.toMutableList()
            } else {
                originalList.filter {
                    it.first.contains(query, ignoreCase = true) ||
                        it.second.contains(query, ignoreCase = true)
                }.toMutableList()
            }
            notifyDataSetChanged()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
