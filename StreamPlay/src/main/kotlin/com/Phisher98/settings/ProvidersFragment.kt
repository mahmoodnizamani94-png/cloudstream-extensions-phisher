package com.phisher98

import android.annotation.SuppressLint
import android.app.Dialog
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

    private data class ProviderRowEntry(
        val checkBox: CheckBox,
        val precedingDivider: View?
    )

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
    private val rowEntries = mutableListOf<ProviderRowEntry>()
    private val PREFS_DISABLED = "disabled_providers"
    private lateinit var tvProviderCount: TextView

    private var activeSubDialog: Dialog? = null
    private var activeSubDialogType: String? = null
    private var draftPresetName: String = ""

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

    private fun refreshFilteredProviderGroup(query: String, density: Float, tvEmpty: TextView?) {
        val normalizedQuery = query.lowercase().trim()
        val visibleEntries = mutableListOf<ProviderRowEntry>()
        for (entry in rowEntries) {
            val matches = normalizedQuery.isEmpty() ||
                entry.checkBox.text.toString().lowercase().contains(normalizedQuery)
            entry.checkBox.visibility = if (matches) View.VISIBLE else View.GONE
            if (matches) {
                visibleEntries.add(entry)
            } else {
                entry.precedingDivider?.visibility = View.GONE
            }
        }
        visibleEntries.forEachIndexed { visibleIdx, entry ->
            entry.precedingDivider?.visibility = if (visibleIdx == 0) View.GONE else View.VISIBLE
            val pos = when {
                visibleEntries.size == 1 -> StreamPlayDesignTokens.GroupPosition.SINGLE
                visibleIdx == 0 -> StreamPlayDesignTokens.GroupPosition.TOP
                visibleIdx == visibleEntries.lastIndex -> StreamPlayDesignTokens.GroupPosition.BOTTOM
                else -> StreamPlayDesignTokens.GroupPosition.MIDDLE
            }
            entry.checkBox.background = StreamPlayDesignTokens.groupedRowDrawable(density, pos)
        }
        val firstVisible = visibleEntries.firstOrNull()?.checkBox
        if (firstVisible != null) {
            firstVisible.isFocusable = true
            firstVisible.nextFocusUpId = btnSaveProfile.id
            btnSaveProfile.nextFocusDownId = firstVisible.id
            btnLoadProfile.nextFocusDownId = firstVisible.id
            btnDeleteProfile.nextFocusDownId = firstVisible.id
        } else {
            btnSaveProfile.nextFocusDownId = View.NO_ID
            btnLoadProfile.nextFocusDownId = View.NO_ID
            btnDeleteProfile.nextFocusDownId = View.NO_ID
        }
        container.visibility = if (visibleEntries.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty?.visibility = if (visibleEntries.isEmpty()) View.VISIBLE else View.GONE
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
        container.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        container.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        providers = buildProviders().sortedBy { it.name.lowercase() }
        rowEntries.clear()

        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        val etSearch = view.findView<EditText>("ext_search")
        etSearch.background = StreamPlayDesignTokens.inputWellDrawable(density)

        val savedDisabled = getOrInitializeDisabledProviders(sharedPref)

        adapter = ProviderAdapter(providers, savedDisabled) { disabled ->
            sharedPref.edit { putStringSet(PREFS_DISABLED, disabled) }
            updateUI()
        }

        providers.forEachIndexed { index, provider ->
            val divider = if (index > 0) {
                View(requireContext()).apply {
                    setBackgroundColor(StreamPlayDesignTokens.BORDER_SUBTLE)
                }.also { div ->
                    container.addView(
                        div,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            StreamPlayDesignTokens.dpToPx(density, 1f)
                        ).apply {
                            marginStart = StreamPlayDesignTokens.dpToPx(density, 16f)
                        }
                    )
                }
            } else {
                null
            }

            val item = getLayout("item_provider_checkbox", layoutInflater, container)
            val chk = item.findViewById<CheckBox>(chkId) ?: (item as CheckBox)
            if (chk.id == View.NO_ID || chk.id == chkId) {
                chk.id = View.generateViewId()
            }

            val isVidlinkMandatory = provider.id.equals("vidlink", ignoreCase = true)
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
            rowEntries.add(ProviderRowEntry(chk, divider))
        }

        refreshFilteredProviderGroup(etSearch.text?.toString().orEmpty(), density, tvEmpty)
        updateProviderCountAndActions()

        etSearch.addTextChangedListener { text ->
            refreshFilteredProviderGroup(text?.toString().orEmpty(), density, tvEmpty)
        }

        etSearch.setOnEditorActionListener { _, _, _ ->
            rowEntries.firstOrNull { it.checkBox.isVisible }?.checkBox?.requestFocus()
            true
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
            openSavePresetDialog()
        }

        btnLoadProfile.setOnClickListener {
            openLoadPresetDialog()
        }

        btnDeleteProfile.setOnClickListener {
            openDeletePresetDialog()
        }

        val restoredSubDialog = savedInstanceState?.getString(STATE_ACTIVE_SUB_DIALOG)
        draftPresetName = savedInstanceState?.getString(STATE_DRAFT_PRESET_NAME).orEmpty()
        when (restoredSubDialog) {
            SUB_DIALOG_SAVE -> openSavePresetDialog(draftPresetName)
            SUB_DIALOG_LOAD -> openLoadPresetDialog()
            SUB_DIALOG_DELETE -> openDeletePresetDialog()
        }
    }

    private fun trackSubDialog(type: String?, dialogInstance: Dialog) {
        activeSubDialog?.setOnDismissListener(null)
        activeSubDialog?.dismiss()
        activeSubDialog = dialogInstance
        activeSubDialogType = type
        dialogInstance.setOnDismissListener {
            if (activeSubDialog === dialogInstance) {
                activeSubDialog = null
                activeSubDialogType = null
            }
        }
    }

    private fun openSavePresetDialog(initialName: String = "") {
        if (activeSubDialog?.isShowing == true) return
        draftPresetName = initialName
        val dlg = StreamPlayDesignTokens.showTextInputDialog(
            context = requireContext(),
            resources = resources,
            title = "Save source preset",
            subtitle = "Name this source selection for quick switching",
            fieldLabel = "Preset name",
            hintText = "e.g. Fast 720p",
            confirmText = "Save preset",
            initialText = initialName,
            onTextChanged = { text -> draftPresetName = text }
        ) { name ->
            draftPresetName = ""
            if (name.isNotEmpty()) {
                saveProfile(name)
                updateProviderCountAndActions()
                showMessage("Preset \"$name\" saved.")
            } else {
                showMessage("Enter a preset name.")
            }
        }
        trackSubDialog(SUB_DIALOG_SAVE, dlg)
    }

    private fun openLoadPresetDialog() {
        if (activeSubDialog?.isShowing == true) return
        val profiles = getAllProfiles().keys.toList()
        if (profiles.isEmpty()) {
            showToast("No saved presets yet.")
            return
        }
        val dlg = StreamPlayDesignTokens.showSelectionListDialog(
            context = requireContext(),
            resources = resources,
            title = "Load source preset",
            subtitle = "Select a saved provider configuration",
            items = profiles
        ) { _, selectedName ->
            loadProfile(selectedName)
        }
        trackSubDialog(SUB_DIALOG_LOAD, dlg)
    }

    private fun openDeletePresetDialog() {
        if (activeSubDialog?.isShowing == true) return
        val profiles = getAllProfiles().keys.toList()
        if (profiles.isEmpty()) {
            showToast("No saved presets to delete.")
            return
        }
        val dlg = StreamPlayDesignTokens.showSelectionListDialog(
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
                val undoDlg = StreamPlayDesignTokens.showConfirmationDialog(
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
                trackSubDialog(null, undoDlg)
            }
        }
        trackSubDialog(SUB_DIALOG_DELETE, dlg)
    }

    private fun updateUI() {
        rowEntries.forEachIndexed { providerIdx, entry ->
            if (providerIdx < providers.size) {
                val id = providers[providerIdx].id
                entry.checkBox.isChecked = if (id.equals("vidlink", ignoreCase = true)) true else !adapter.isDisabled(id)
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
        private const val STATE_ACTIVE_SUB_DIALOG = "state_active_sub_dialog"
        private const val STATE_DRAFT_PRESET_NAME = "state_draft_preset_name"
        private const val SUB_DIALOG_SAVE = "save"
        private const val SUB_DIALOG_LOAD = "load"
        private const val SUB_DIALOG_DELETE = "delete"

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ACTIVE_SUB_DIALOG, activeSubDialogType)
        outState.putString(STATE_DRAFT_PRESET_NAME, draftPresetName)
    }

    override fun onDestroyView() {
        activeSubDialog?.setOnDismissListener(null)
        activeSubDialog?.dismiss()
        activeSubDialog = null
        rowEntries.clear()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        StreamPlayDesignTokens.notifyParentSettingsChanged(this, onDismissCallback)
    }
}
