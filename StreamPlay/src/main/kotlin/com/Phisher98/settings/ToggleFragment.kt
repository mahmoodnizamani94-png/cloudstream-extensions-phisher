package com.phisher98.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.phisher98.BuildConfig
import com.phisher98.StreamPlay
import com.phisher98.StreamPlayAnime
import com.phisher98.StreamPlayDesignTokens
import com.phisher98.StreamPlayPlugin
import com.phisher98.StreamPlayStremioCatelog
import com.phisher98.StreamPlayStremioCatelogFrag

class ToggleFragment(
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
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources)
    }

    @SuppressLint("DiscouragedApi")
    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw IllegalStateException("Layout $name not found.")
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Resources.NotFoundException("View ID $name not found.")
        return this.findViewById(id)
    }

    private fun <T : View> View.findViewOrNull(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return runCatching { this.findViewById<T>(id) }.getOrNull()
    }

    private fun loadStremioLinks(): List<StreamPlayStremioCatelogFrag.LinkItem> {
        val json = sharedPref.getString("streamplay_stremio_saved_links", null)
            ?: return emptyList()

        val list = mutableListOf<StreamPlayStremioCatelogFrag.LinkItem>()

        return try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    StreamPlayStremioCatelogFrag.LinkItem(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", ""),
                        link = obj.optString("link", ""),
                        type = obj.optString("type", "StremioC")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = getLayout("fragment_toggle_extensions", inflater, container)
        val density = resources.displayMetrics.density
        root.background = StreamPlayDesignTokens.dialogSurfaceDrawable(resources)

        val extensionList = root.findView<LinearLayout>("toggle_list_container")
        extensionList.background = StreamPlayDesignTokens.groupedSectionDrawable(density)

        val subtitleCount = root.findViewOrNull<TextView>("subtitle_count")
        val stremioLinks = loadStremioLinks()
        val apis = buildList {
            add(StreamPlay(sharedPref))
            add(StreamPlayAnime())
            if (stremioLinks.isNotEmpty()) {
                stremioLinks.forEach { link ->
                    add(
                        StreamPlayStremioCatelog(
                            link.link,
                            link.name,
                            sharedPref
                        )
                    )
                }
            }
        }

        val savedKey = "enabled_plugins_saved"
        val savedSet = sharedPref.getStringSet(savedKey, null)
        val defaultEnabled = apis.map { it.name }.toSet()
        val activeNames = (savedSet?.toSet() ?: defaultEnabled).toMutableSet()

        fun persistAndRefreshCount() {
            sharedPref.edit {
                putStringSet(savedKey, activeNames.toSet())
            }
            subtitleCount?.text = "${activeNames.size} of ${apis.size} enabled"
        }

        val toggleItemId = res.getIdentifier("toggle_item", "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        apis.forEachIndexed { index, api ->
            if (index > 0) {
                val divider = View(requireContext()).apply {
                    setBackgroundColor(StreamPlayDesignTokens.BORDER_SUBTLE)
                }
                extensionList.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        StreamPlayDesignTokens.dpToPx(density, 1f)
                    ).apply {
                        marginStart = StreamPlayDesignTokens.dpToPx(density, 16f)
                    }
                )
            }

            val groupPosition = when {
                apis.size == 1 -> StreamPlayDesignTokens.GroupPosition.SINGLE
                index == 0 -> StreamPlayDesignTokens.GroupPosition.TOP
                index == apis.lastIndex -> StreamPlayDesignTokens.GroupPosition.BOTTOM
                else -> StreamPlayDesignTokens.GroupPosition.MIDDLE
            }

            val toggleItem = getLayout("list_toggle_item", inflater, container)
            val toggleSwitch = toggleItem.findViewById<Switch>(toggleItemId)
            toggleItem.background = StreamPlayDesignTokens.groupedRowDrawable(density, groupPosition)
            toggleSwitch.text = api.name
            toggleSwitch.setTextColor(StreamPlayDesignTokens.TEXT_PRIMARY)
            toggleSwitch.isChecked = activeNames.contains(api.name)
            toggleSwitch.thumbTintList = StreamPlayDesignTokens.switchThumbTintList()
            toggleSwitch.trackTintList = StreamPlayDesignTokens.switchTrackTintList()

            toggleSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
                if (!isChecked && activeNames.size <= 1 && activeNames.contains(api.name)) {
                    buttonView.isChecked = true
                    buttonView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast("At least one extension must remain enabled")
                    return@setOnCheckedChangeListener
                }
                buttonView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (isChecked) {
                    activeNames.add(api.name)
                } else {
                    activeNames.remove(api.name)
                }
                persistAndRefreshCount()
            }

            toggleItem.setOnClickListener {
                toggleSwitch.isChecked = !toggleSwitch.isChecked
            }

            extensionList.addView(toggleItem)
        }

        persistAndRefreshCount()

        val saveBtn = root.findView<TextView>("saveIcon")
        StreamPlayDesignTokens.styleButton(saveBtn, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)

        saveBtn.setOnClickListener {
            dismiss()
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        StreamPlayDesignTokens.notifyParentSettingsChanged(this, onDismissCallback)
    }
}
