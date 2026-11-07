package com.phisher98

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LanguageSelectFragment(
    private var explicitPlugin: StreamPlayPlugin? = null,
    private var explicitSharedPref: SharedPreferences? = null,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    constructor() : this(null, null, null)

    private val res: Resources
        get() = StreamPlayDesignTokens.resolvePluginResources(this, explicitPlugin)

    private val sharedPref: SharedPreferences
        get() = StreamPlayDesignTokens.resolveSharedPreferences(this, explicitSharedPref)

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources, maxHeightFraction = 0.85f)
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

        val btnClose: Button? = root.findViewOrNull("btn_close_language")
        val tvEmpty: TextView? = root.findViewOrNull("tv_empty_language")
        val recycler: RecyclerView = root.findView("languageRecycler")
        val search: EditText = root.findView("searchLanguage")

        btnClose?.let {
            StreamPlayDesignTokens.styleButton(it, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
            it.setOnClickListener { dismiss() }
        }
        search.background = StreamPlayDesignTokens.inputWellDrawable(density)
        recycler.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        StreamPlayDesignTokens.attachGroupedListDivider(recycler, density)

        val savedCode = sharedPref.getString("tmdb_language_code", "en-US") ?: "en-US"

        adapter = LanguageAdapter(
            languageList,
            savedCode,
            density
        ) { code ->
            sharedPref.edit { putString("tmdb_language_code", code) }
            Toast.makeText(requireContext(), "Catalog language set to $code", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        recycler.adapter = adapter

        search.addTextChangedListener { text ->
            val count = adapter.filter(text.toString())
            recycler.invalidateItemDecorations()
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

        private fun groupPositionFor(index: Int, size: Int): StreamPlayDesignTokens.GroupPosition = when {
            size <= 1 -> StreamPlayDesignTokens.GroupPosition.SINGLE
            index == 0 -> StreamPlayDesignTokens.GroupPosition.TOP
            index == size - 1 -> StreamPlayDesignTokens.GroupPosition.BOTTOM
            else -> StreamPlayDesignTokens.GroupPosition.MIDDLE
        }

        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findView("radio_language")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = getLayout("item_language", LayoutInflater.from(parent.context), parent)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, code) = filteredList[position]
            val pos = groupPositionFor(position, filteredList.size)

            holder.radio.text = name
            holder.radio.isChecked = code == selectedCode
            holder.radio.setTextColor(StreamPlayDesignTokens.TEXT_PRIMARY)
            holder.radio.background = StreamPlayDesignTokens.groupedRowDrawable(density, pos)
            CompoundButtonCompat.setButtonTintList(
                holder.radio,
                StreamPlayDesignTokens.compoundButtonTintList()
            )

            holder.radio.setOnClickListener { buttonView ->
                buttonView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousList.size
                override fun getNewListSize(): Int = nextList.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousList[oldItemPosition].second == nextList[newItemPosition].second
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousList[oldItemPosition] == nextList[newItemPosition] &&
                        groupPositionFor(oldItemPosition, previousList.size) ==
                        groupPositionFor(newItemPosition, nextList.size)
            })
            filteredList = nextList
            diff.dispatchUpdatesTo(this)
            return filteredList.size
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        StreamPlayDesignTokens.notifyParentSettingsChanged(this, onDismissCallback)
    }
}
