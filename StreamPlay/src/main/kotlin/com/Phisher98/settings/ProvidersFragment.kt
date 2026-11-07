package com.phisher98

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

private const val PREFS_PROFILES = "provider_profiles"

class ProvidersFragment(
    private var explicitPlugin: StreamPlayPlugin? = null,
    private var explicitSharedPref: SharedPreferences? = null,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    constructor() : this(null, null, null)

    private val res: Resources
        get() = StreamPlayDesignTokens.resolvePluginResources(this, explicitPlugin)

    private val sharedPref: SharedPreferences
        get() = StreamPlayDesignTokens.resolveSharedPreferences(this, explicitSharedPref)

    private lateinit var btnSave: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
    private lateinit var btnSaveProfile: Button
    private lateinit var btnLoadProfile: Button
    private lateinit var btnDeleteProfile: Button
    private var spaceLoadProfile: View? = null
    private var spaceDeleteProfile: View? = null
    private lateinit var adapter: ProviderAdapter
    private lateinit var container: LinearLayout
    private var providers: List<Provider> = emptyList()
    private val PREFS_DISABLED = "disabled_providers"
    private lateinit var tvProviderCount: TextView

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = getLayout("fragment_providers", inflater, container)
        view.background = StreamPlayDesignTokens.dialogSheetDrawable(resources.displayMetrics.density)
        return view
    }

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources)
    }

    @SuppressLint("SetTextI18n")
    private fun updateProviderCountAndActions() {
        val enabled = providers.count { !adapter.isDisabled(it.id) }
        val total = providers.size
        tvProviderCount.text = "$enabled of $total enabled"
        tvProviderCount.setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)

        val optionalCount = providers.count { !it.id.equals("vidlink", ignoreCase = true) }
        val enabledOptionalCount = providers.count { !it.id.equals("vidlink", ignoreCase = true) && !adapter.isDisabled(it.id) }
        btnSelectAll.isEnabled = enabledOptionalCount < optionalCount
        btnDeselectAll.isEnabled = enabledOptionalCount > 0

        val hasPresets = getAllProfiles().isNotEmpty()
        spaceLoadProfile?.visibility = if (hasPresets) View.VISIBLE else View.GONE
        btnLoadProfile.visibility = if (hasPresets) View.VISIBLE else View.GONE
        spaceDeleteProfile?.visibility = if (hasPresets) View.VISIBLE else View.GONE
        btnDeleteProfile.visibility = if (hasPresets) View.VISIBLE else View.GONE
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)

        tvProviderCount = view.findView("tv_provider_count")
        val tvEmpty = view.findViewOrNull<TextView>("tv_empty_providers")
        btnSave = view.findView("btn_save")
        StreamPlayDesignTokens.styleButton(btnSave, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)

        btnSelectAll = view.findView("btn_select_all")
        btnDeselectAll = view.findView("btn_deselect_all")
        StreamPlayDesignTokens.styleButton(btnSelectAll, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnDeselectAll, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.applyDirectionalFocus(btnSelectAll, btnDeselectAll)

        btnSaveProfile = view.findView("btn_save_profile")
        btnLoadProfile = view.findView("btn_load_profile")
        btnDeleteProfile = view.findView("btn_delete_profile")
        spaceLoadProfile = view.findViewOrNull("space_load_profile")
        spaceDeleteProfile = view.findViewOrNull("space_delete_profile")

        StreamPlayDesignTokens.styleButton(btnSaveProfile, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnLoadProfile, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnDeleteProfile, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.applyDirectionalFocus(btnSaveProfile, btnLoadProfile)
        StreamPlayDesignTokens.applyDirectionalFocus(btnLoadProfile, btnDeleteProfile)

        container = view.findView("list_container")
        container.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        providers = buildProviders().sortedBy { it.name.lowercase() }

        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        val etSearch = view.findView<EditText>("ext_search")
        etSearch.background = StreamPlayDesignTokens.inputWellDrawable(density)
        etSearch.addTextChangedListener { text ->
            val query = text.toString().lowercase().trim()
            var visibleCount = 0
            for (i in 0 until container.childCount) {
                val item = container.getChildAt(i)
                val chk = item.findViewById<CheckBox>(chkId)
                if (chk != null) {
                    val matches = query.isEmpty() || chk.text.toString().lowercase().contains(query)
                    item.visibility = if (matches) View.VISIBLE else View.GONE
                    if (matches) visibleCount++
                } else {
                    // Hairline divider visibility tracks adjacent items
                    item.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            container.visibility = if (visibleCount == 0) View.GONE else View.VISIBLE
            tvEmpty?.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        }

        etSearch.setOnEditorActionListener { _, _, _ ->
            for (i in 0 until container.childCount) {
                val item = container.getChildAt(i)
                if (item is CheckBox && item.isVisible) {
                    item.requestFocus()
                    break
                }
            }
            true
        }

        val savedDisabled = getOrInitializeDisabledProviders(sharedPref)

        adapter = ProviderAdapter(providers, savedDisabled) { disabled ->
            sharedPref.edit { putStringSet(PREFS_DISABLED, disabled) }
            updateUI()
        }

        var firstCheckBox: CheckBox? = null
        providers.forEachIndexed { index, provider ->
            if (index > 0) {
                val divider = View(requireContext()).apply {
                    setBackgroundColor(StreamPlayDesignTokens.BORDER_SUBTLE)
                }
                container.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        StreamPlayDesignTokens.dpToPx(density, 1f)
                    ).apply {
                        marginStart = StreamPlayDesignTokens.dpToPx(density, 14f)
                    }
                )
            }

            val item = getLayout("item_provider_checkbox", layoutInflater, container)
            val chk = item.findViewById<CheckBox>(chkId) ?: (item as CheckBox)
            if (firstCheckBox == null) firstCheckBox = chk

            val isVidlinkMandatory = provider.id.equals("vidlink", ignoreCase = true)
            val groupPosition = when {
                providers.size == 1 -> StreamPlayDesignTokens.GroupPosition.SINGLE
                index == 0 -> StreamPlayDesignTokens.GroupPosition.TOP
                index == providers.lastIndex -> StreamPlayDesignTokens.GroupPosition.BOTTOM
                else -> StreamPlayDesignTokens.GroupPosition.MIDDLE
            }

            chk.background = StreamPlayDesignTokens.groupedRowDrawable(density, groupPosition)
            CompoundButtonCompat.setButtonTintList(
                chk,
                StreamPlayDesignTokens.compoundButtonTintList()
            )
            chk.text = provider.name
            chk.setTextColor(
                if (isVidlinkMandatory) StreamPlayDesignTokens.TEXT_SECONDARY else StreamPlayDesignTokens.TEXT_PRIMARY
            )
            chk.isChecked = if (isVidlinkMandatory) true else !adapter.isDisabled(provider.id)
            chk.isEnabled = !isVidlinkMandatory

            chk.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isVidlinkMandatory) {
                    chk.isChecked = true
                } else {
                    buttonView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    adapter.setDisabled(provider.id, !isChecked)
                }
            }

            container.addView(item)
        }
        updateProviderCountAndActions()

        container.post {
            firstCheckBox?.let { first ->
                first.isFocusable = true
                first.nextFocusUpId = btnSaveProfile.id
                btnSaveProfile.nextFocusDownId = first.id
                btnLoadProfile.nextFocusDownId = first.id
                btnDeleteProfile.nextFocusDownId = first.id
            }
        }

        btnSelectAll.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            adapter.setAll(true)
        }
        btnDeselectAll.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            adapter.setAll(false)
        }
        btnSave.setOnClickListener { dismissFragment() }

        btnSaveProfile.setOnClickListener {
            StreamPlayDesignTokens.showTextInputDialog(
                context = requireContext(),
                resources = resources,
                title = "Save source preset",
                subtitle = "Name this source selection for quick switching",
                fieldLabel = "Preset name",
                hintText = "e.g. Fast 720p",
                confirmText = "Save preset"
            ) { name ->
                if (name.isNotEmpty()) {
                    saveProfile(name)
                    updateProviderCountAndActions()
                    showMessage("Preset \"$name\" saved.")
                } else {
                    showMessage("Enter a preset name.")
                }
            }
        }

        btnLoadProfile.setOnClickListener {
            val profiles = getAllProfiles().keys.toList()
            if (profiles.isEmpty()) {
                showToast("No saved presets yet.")
                return@setOnClickListener
            }

            StreamPlayDesignTokens.showSelectionListDialog(
                context = requireContext(),
                resources = resources,
                title = "Load source preset",
                subtitle = "Select a saved provider configuration",
                items = profiles
            ) { _, selectedName ->
                loadProfile(selectedName)
            }
        }

        btnDeleteProfile.setOnClickListener {
            val profiles = getAllProfiles().keys.toList()
            if (profiles.isEmpty()) {
                showToast("No saved presets to delete.")
                return@setOnClickListener
            }

            StreamPlayDesignTokens.showSelectionListDialog(
                context = requireContext(),
                resources = resources,
                title = "Delete source preset",
                subtitle = "Select a preset to remove",
                items = profiles,
                itemTextColor = StreamPlayDesignTokens.DANGER_TEXT
            ) { _, selectedName ->
                val backupSet = getAllProfiles()[selectedName]
                deleteProfile(selectedName)
                updateProviderCountAndActions()
                if (backupSet != null) {
                    StreamPlayDesignTokens.showConfirmationDialog(
                        context = requireContext(),
                        resources = resources,
                        title = "Preset \"$selectedName\" deleted",
                        message = "Restore this preset?",
                        confirmText = "Undo",
                        cancelText = "Keep deleted",
                        confirmVariant = StreamPlayDesignTokens.ButtonVariant.PRIMARY
                    ) {
                        restoreProfile(selectedName, backupSet)
                        updateProviderCountAndActions()
                        showMessage("Preset \"$selectedName\" restored.")
                    }
                }
            }
        }
    }

    private fun updateUI() {
        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        var providerIdx = 0
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val chk = child.findViewById<CheckBox>(chkId) ?: (child as? CheckBox)
            if (chk != null && providerIdx < providers.size) {
                val id = providers[providerIdx].id
                chk.isChecked = if (id.equals("vidlink", ignoreCase = true)) true else !adapter.isDisabled(id)
                providerIdx++
            }
        }
        updateProviderCountAndActions()
    }

    private fun dismissFragment() {
        dismiss()
    }

    inner class ProviderAdapter(
        private val items: List<Provider>,
        initiallyDisabled: Set<String>,
        private val onChange: (Set<String>) -> Unit
    ) {
        private val disabled = initiallyDisabled.filterNot { it.equals("vidlink", ignoreCase = true) }.toMutableSet()

        fun isDisabled(id: String) = !id.equals("vidlink", ignoreCase = true) && id in disabled

        fun setDisabled(id: String, value: Boolean) {
            if (id.equals("vidlink", ignoreCase = true)) return
            if (value) disabled.add(id) else disabled.remove(id)
            onChange(disabled)
        }

        fun setAll(value: Boolean) {
            disabled.clear()
            if (!value) disabled.addAll(items.map { it.id }.filterNot { it.equals("vidlink", ignoreCase = true) })
            onChange(disabled)
        }
    }

    private fun saveProfile(name: String) {
        val disabled = sharedPref.getStringSet(PREFS_DISABLED, emptySet()) ?: emptySet()
        restoreProfile(name, disabled)
    }

    private fun restoreProfile(name: String, disabled: Set<String>) {
        val allProfiles = getAllProfiles().toMutableMap()
        allProfiles[name] = disabled
        sharedPref.edit { putString(PREFS_PROFILES, encodeProfilesMap(allProfiles)) }
    }

    private fun getAllProfiles(): Map<String, Set<String>> {
        val encoded = sharedPref.getString(PREFS_PROFILES, "") ?: return emptyMap()
        return decodeProfilesMap(encoded)
    }

    private fun loadProfile(name: String) {
        val profiles = getAllProfiles()
        val disabled = profiles[name] ?: return
        sharedPref.edit { putStringSet(PREFS_DISABLED, disabled) }
        adapter = ProviderAdapter(providers, disabled) { updated ->
            sharedPref.edit { putStringSet(PREFS_DISABLED, updated) }
            updateUI()
        }
        updateUI()
        showMessage("Preset \"$name\" loaded.")
    }

    private fun deleteProfile(name: String) {
        val allProfiles = getAllProfiles().toMutableMap()
        if (allProfiles.remove(name) != null) {
            sharedPref.edit { putString(PREFS_PROFILES, encodeProfilesMap(allProfiles)) }
        }
    }

    companion object {
        internal fun encodeProfilesMap(profiles: Map<String, Set<String>>): String {
            return profiles.entries.joinToString("|") { (key, value) ->
                val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
                "$encodedKey:${value.joinToString(",")}"
            }
        }

        internal fun decodeProfilesMap(encoded: String): Map<String, Set<String>> {
            if (encoded.isEmpty()) return emptyMap()
            return buildMap {
                encoded.split("|").forEach { entry ->
                    val separatorIndex = entry.indexOf(':')
                    if (separatorIndex < 0) return@forEach

                    val rawName = entry.substring(0, separatorIndex)
                    val name = runCatching {
                        java.net.URLDecoder.decode(rawName, "UTF-8")
                    }.getOrDefault(rawName)
                    val idsPart = entry.substring(separatorIndex + 1)
                    val ids = if (idsPart.isEmpty()) {
                        emptySet()
                    } else {
                        idsPart.split(",").toSet()
                    }
                    put(name, ids)
                }
            }
        }
    }

    private fun showMessage(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        StreamPlayDesignTokens.notifyParentSettingsChanged(this, onDismissCallback)
    }
}
