package com.phisher98

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

private const val PREFS_PROFILES = "provider_profiles"

class ProvidersFragment(
    private val plugin: StreamPlayPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private lateinit var btnSave: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
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
    private fun updateProviderCount() {
        val enabled = providers.count { !adapter.isDisabled(it.id) }
        val total = providers.size
        tvProviderCount.text = "$enabled / $total active · Saved automatically"
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
        StreamPlayDesignTokens.styleButton(btnSelectAll, density, StreamPlayDesignTokens.ButtonVariant.PRIMARY)
        StreamPlayDesignTokens.styleButton(btnDeselectAll, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.applyDirectionalFocus(btnSelectAll, btnDeselectAll)

        container = view.findView("list_container")
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
                val matches = query.isEmpty() || chk.text.toString().lowercase().contains(query)
                item.visibility = if (matches) View.VISIBLE else View.GONE
                if (matches) visibleCount++
            }
            tvEmpty?.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        }

        etSearch.setOnEditorActionListener { _, _, _ ->
            for (i in 0 until container.childCount) {
                val item = container.getChildAt(i)
                if (item.isVisible) {
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
            updateProviderCount()
        }

        providers.forEach { provider ->
            val item = getLayout("item_provider_checkbox", layoutInflater, container)
            val chk = item.findViewById<CheckBox>(chkId)
            val isVidlinkMandatory = provider.id.equals("vidlink", ignoreCase = true)

            chk.background = StreamPlayDesignTokens.listItemDrawable(density)
            CompoundButtonCompat.setButtonTintList(
                chk,
                StreamPlayDesignTokens.compoundButtonTintList()
            )
            chk.text = if (isVidlinkMandatory) {
                "${provider.name} · Always enabled (primary source)"
            } else {
                provider.name
            }
            chk.setTextColor(
                if (isVidlinkMandatory) StreamPlayDesignTokens.ACCENT_SOFT else StreamPlayDesignTokens.TEXT_PRIMARY
            )
            chk.isChecked = if (isVidlinkMandatory) true else !adapter.isDisabled(provider.id)
            chk.isEnabled = !isVidlinkMandatory

            item.setOnClickListener {
                if (!isVidlinkMandatory) chk.toggle()
            }

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
        updateProviderCount()

        container.post {
            if (container.isNotEmpty()) {
                val firstItem = container.getChildAt(0)
                firstItem.isFocusable = true
                firstItem.nextFocusUpId = btnSave.id
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

        val btnSaveProfile = view.findView<Button>("btn_save_profile")
        val btnLoadProfile = view.findView<Button>("btn_load_profile")
        val btnDeleteProfile = view.findView<Button>("btn_delete_profile")

        StreamPlayDesignTokens.styleButton(btnSaveProfile, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnLoadProfile, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
        StreamPlayDesignTokens.styleButton(btnDeleteProfile, density, StreamPlayDesignTokens.ButtonVariant.DANGER)
        StreamPlayDesignTokens.applyDirectionalFocus(btnSaveProfile, btnLoadProfile)
        StreamPlayDesignTokens.applyDirectionalFocus(btnLoadProfile, btnDeleteProfile)

        btnSaveProfile.setOnClickListener {
            val padH = StreamPlayDesignTokens.dpToPx(density, 14f)
            val padV = StreamPlayDesignTokens.dpToPx(density, 12f)
            val input = EditText(requireContext()).apply {
                hint = "Preset name (e.g. Fast 720p)"
                setSingleLine()
                setTextColor(StreamPlayDesignTokens.TEXT_PRIMARY)
                setHintTextColor(StreamPlayDesignTokens.TEXT_MUTED)
                background = StreamPlayDesignTokens.inputWellDrawable(density)
                setPadding(padH, padV, padH, padV)
            }

            val dialog = android.app.AlertDialog.Builder(requireContext())
                .setTitle("Save source preset")
                .setMessage("Name this source selection:")
                .setView(input)
                .setPositiveButton("Save preset") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        saveProfile(name)
                        showMessage("Preset \"$name\" saved.")
                    } else {
                        showMessage("Enter a preset name.")
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()

            dialog.setOnShowListener {
                input.isFocusableInTouchMode = true
                input.requestFocus()
            }

            dialog.show()
        }

        btnLoadProfile.setOnClickListener {
            val profiles = getAllProfiles().keys.toTypedArray()
            if (profiles.isEmpty()) {
                showToast("No saved presets yet. Use Save preset to create one.")
                return@setOnClickListener
            }

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Load source preset")
                .setItems(profiles) { _, which ->
                    loadProfile(profiles[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnDeleteProfile.setOnClickListener {
            val profiles = getAllProfiles().keys.toTypedArray()
            if (profiles.isEmpty()) {
                showToast("No saved presets to delete.")
                return@setOnClickListener
            }
            val dialog = android.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete source preset")
                .setItems(profiles) { _, which ->
                    val selectedName = profiles[which]
                    val backupSet = getAllProfiles()[selectedName]
                    deleteProfile(selectedName)
                    if (backupSet != null) {
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("Preset \"$selectedName\" deleted")
                            .setMessage("Would you like to undo and restore this preset?")
                            .setPositiveButton("Undo") { _, _ ->
                                restoreProfile(selectedName, backupSet)
                                showMessage("Preset \"$selectedName\" restored.")
                            }
                            .setNegativeButton("Keep deleted", null)
                            .show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.setOnShowListener {
                dialog.listView?.let { list ->
                    if (list.isNotEmpty()) {
                        list.getChildAt(0)?.requestFocus()
                    }
                }
            }
            dialog.show()
        }
    }

    private fun updateUI() {
        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        for (i in 0 until container.childCount) {
            val chk = container.getChildAt(i).findViewById<CheckBox>(chkId)
            val id = providers[i].id
            chk.isChecked = if (id.equals("vidlink", ignoreCase = true)) true else !adapter.isDisabled(id)
        }
        updateProviderCount()
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
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
