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
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LanguageSelectFragment(
    plugin: StreamPlayPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources, maxHeightFraction = 0.85f)
        dialog?.window?.apply {
            val displayMetrics = resources.displayMetrics
            val height = (displayMetrics.heightPixels * 0.85f).toInt().coerceAtLeast(1)
            setLayout(attributes.width, height)
        }
    }

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

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

    private fun <T : View> View.findViewOrNull(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return runCatching { this.findViewById<T>(id) }.getOrNull()
    }

    private val pinnedPriorityLanguages = listOf(
        "Urdu · Pakistan (ur-PK)" to "ur-PK",
        "Urdu · India (ur-IN)" to "ur-IN",
        "English · United States (en-US)" to "en-US",
        "English · United Kingdom (en-GB)" to "en-GB"
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

    @SuppressLint("SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = getLayout("fragment_language_select", inflater, container)
        val density = resources.displayMetrics.density
        root.background = StreamPlayDesignTokens.dialogSheetDrawable(density)

        val rootContainer: ViewGroup? = root.findViewOrNull("language_select_root")
        val banner: View? = root.findViewOrNull("lang_subtitle_mode_banner")
        val bannerStatus: TextView? = root.findViewOrNull("lang_subtitle_mode_status")
        val pillTrack: View? = root.findViewOrNull("lang_pill_track")
        val pillDefault: TextView? = root.findViewOrNull("lang_pill_default")
        val pillUrdu: TextView? = root.findViewOrNull("lang_pill_urdu")
        val tvEmpty: TextView? = root.findViewOrNull("tv_empty_language")

        val recycler: RecyclerView = root.findView("languageRecycler")
        val search: EditText = root.findView("searchLanguage")
        search.background = StreamPlayDesignTokens.inputWellDrawable(density)

        banner?.background = StreamPlayDesignTokens.staticCardDrawable(density)
        pillTrack?.background = StreamPlayDesignTokens.segmentedTrackDrawable(density)
        StreamPlayDesignTokens.applyDirectionalFocus(pillDefault, pillUrdu)

        fun updateSubtitleBannerUi(animate: Boolean = false) {
            if (animate) {
                StreamPlayDesignTokens.beginMicroTransition(rootContainer, context)
            }
            val currentMode = UrduSubtitleEngine.getSubtitleMode(context, sharedPref)
            val isUrdu = currentMode == UrduSubtitleEngine.SubtitleMode.URDU

            pillDefault?.text = "English"
            pillUrdu?.text = "Urdu ${StreamPlayDesignTokens.isolateRtl("\u0627\u0631\u062f\u0648")}"

            if (isUrdu) {
                val ready = StreamPlayDesignTokens.countReadyUrduSources(context, sharedPref)
                bannerStatus?.text =
                    "$ready of ${UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES} sources ready · Streams and downloads"
                bannerStatus?.setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                pillUrdu?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = true)
                    setTextColor(StreamPlayDesignTokens.ON_ACCENT)
                    isSelected = true
                }
                pillDefault?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = false)
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                    isSelected = false
                }
            } else {
                val readyEnglish = StreamPlayDesignTokens.countReadyEnglishSources(context, sharedPref)
                bannerStatus?.text = "$readyEnglish of 6 sources ready · Streams and downloads"
                bannerStatus?.setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                pillDefault?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = true)
                    setTextColor(StreamPlayDesignTokens.ON_ACCENT)
                    isSelected = true
                }
                pillUrdu?.apply {
                    background = StreamPlayDesignTokens.segmentOptionDrawable(density, selected = false)
                    setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
                    isSelected = false
                }
            }
        }

        updateSubtitleBannerUi(animate = false)

        fun applySubtitleModeFromBanner(selectedMode: UrduSubtitleEngine.SubtitleMode) {
            val ctx = context ?: return
            val prev = UrduSubtitleEngine.getSubtitleMode(ctx, sharedPref)
            UrduSubtitleEngine.setSubtitleMode(
                mode = selectedMode,
                context = ctx,
                sharedPref = sharedPref
            )
            updateSubtitleBannerUi(animate = prev != selectedMode)
        }

        pillDefault?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            applySubtitleModeFromBanner(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        }

        pillUrdu?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            applySubtitleModeFromBanner(UrduSubtitleEngine.SubtitleMode.URDU)
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())

        val savedCode = sharedPref.getString("tmdb_language_code", "en-US") ?: "en-US"

        adapter = LanguageAdapter(
            languageList,
            savedCode,
            density
        ) { code ->
            sharedPref.edit { putString("tmdb_language_code", code) }
            val ctx = requireContext()
            if (code.startsWith("ur", ignoreCase = true)) {
                UrduSubtitleEngine.setSubtitleMode(
                    mode = UrduSubtitleEngine.SubtitleMode.URDU,
                    context = ctx,
                    sharedPref = sharedPref
                )
                updateSubtitleBannerUi(animate = false)
                Toast.makeText(
                    ctx,
                    "Language set to $code (Urdu subtitles active)",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (code.startsWith("en", ignoreCase = true)) {
                UrduSubtitleEngine.setSubtitleMode(
                    mode = UrduSubtitleEngine.SubtitleMode.DEFAULT,
                    context = ctx,
                    sharedPref = sharedPref
                )
                updateSubtitleBannerUi(animate = false)
                Toast.makeText(
                    ctx,
                    "Language set to $code (English subtitles active)",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(ctx, "Language set to $code", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }

        recycler.adapter = adapter

        search.addTextChangedListener { text ->
            val count = adapter.filter(text.toString())
            if (count == 0) {
                tvEmpty?.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                tvEmpty?.visibility = View.GONE
                recycler.visibility = View.VISIBLE
            }
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)
    }

    inner class LanguageAdapter(
        private val originalList: List<Pair<String, String>>,
        private val selectedCode: String,
        private val density: Float,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.VH>() {

        private var filteredList: List<Pair<String, String>> = originalList

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
            holder.radio.setTextColor(StreamPlayDesignTokens.TEXT_PRIMARY)
            holder.radio.background = StreamPlayDesignTokens.listItemDrawable(density)
            CompoundButtonCompat.setButtonTintList(
                holder.radio,
                StreamPlayDesignTokens.compoundButtonTintList()
            )

            holder.radio.setOnClickListener {
                onClick(code)
            }
        }

        override fun getItemCount() = filteredList.size

        fun filter(query: String): Int {
            val previousList = filteredList
            val nextList = if (query.isBlank()) {
                originalList
            } else {
                originalList.filter {
                    it.first.contains(query, ignoreCase = true) ||
                        it.second.contains(query, ignoreCase = true)
                }
            }
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousList.size
                override fun getNewListSize(): Int = nextList.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousList[oldItemPosition].second == nextList[newItemPosition].second
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousList[oldItemPosition] == nextList[newItemPosition]
            })
            filteredList = nextList
            diff.dispatchUpdatesTo(this)
            return filteredList.size
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
