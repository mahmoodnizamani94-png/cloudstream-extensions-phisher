package com.phisher98

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.widget.CompoundButtonCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject

class StreamPlayStremioAddonFrag() : DialogFragment() {

    private var pluginRef: StreamPlayPlugin? = null
    private var sharedPrefRef: SharedPreferences? = null
    private var onDismissCallback: (() -> Unit)? = null

    constructor(
        plugin: StreamPlayPlugin?,
        sharedPref: SharedPreferences?,
        onDismissCallback: (() -> Unit)? = null
    ) : this() {
        this.pluginRef = plugin
        this.sharedPrefRef = sharedPref
        this.onDismissCallback = onDismissCallback
    }

    override fun onStart() {
        super.onStart()
        StreamPlayDesignTokens.configureDialogWindow(dialog, resources)
    }

    private val PREF_KEY_LINKS = StreamPlayStremioAddonSettings.PREF_KEY_LINKS
    private val res: Resources
        get() = StreamPlayDesignTokens.resolvePluginResources(this, pluginRef)
    private val sharedPref: SharedPreferences
        get() = StreamPlayDesignTokens.resolveSharedPreferences(this, sharedPrefRef)

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return ResourcesCompat.getDrawable(res, id, null) ?: throw IllegalStateException("Drawable $name not found")
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

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun formatAddonTypeLabel(rawType: String): String = when (rawType.uppercase()) {
        "HTTPS" -> "HTTPS"
        "TORRENT" -> "Torrent"
        "SUBTITLE" -> "Subtitle"
        "DEBRID" -> "Debrid"
        else -> rawType
    }

    @SuppressLint("DiscouragedApi", "SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = getLayout("streamplay_stremio_addon_bottom_sheet_layout", inflater, container)
        val density = resources.displayMetrics.density
        view.background = StreamPlayDesignTokens.dialogSurfaceDrawable(resources)

        val addonActionsGroup: View? = view.findViewOrNull("addon_actions_group")
        val rowAddLink: View = view.findView("row_add_link")
        val rowShowLinks: View = view.findView("row_show_links")
        val savedLinksCountText: TextView = view.findView("saved_links_count_text")
        val addlinks: ImageView = view.findView("addlinks")
        val showlinks: ImageView = view.findView("showlinks")
        val saveIcon: ImageView = view.findView("saveIcon")

        val chevronDrawable = getDrawable("chevron_right_icon")
        addlinks.setImageDrawable(chevronDrawable.constantState?.newDrawable()?.mutate() ?: chevronDrawable)
        showlinks.setImageDrawable(chevronDrawable.constantState?.newDrawable()?.mutate() ?: chevronDrawable)
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        addonActionsGroup?.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        rowAddLink.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.TOP)
        rowShowLinks.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.BOTTOM)
        saveIcon.background = StreamPlayDesignTokens.iconButtonDrawable(resources)

        fun refreshSavedCount() {
            val count = loadLinks().size
            savedLinksCountText.text = when (count) {
                0 -> "No addon manifests saved"
                1 -> "1 addon manifest configured"
                else -> "$count addon manifests configured"
            }
        }
        refreshSavedCount()

        val openAddDialog = View.OnClickListener {
            val dialogView = getLayout("streamio_addon_addlinks", inflater, container)
            val etName: EditText
            val etLink: EditText
            val rg: RadioGroup
            val btnCancel: Button
            val btnSave: Button
            try {
                etName = dialogView.findView("etName")
                etLink = dialogView.findView("etLink")
                rg = dialogView.findView("radioGroup")
                btnCancel = dialogView.findView("btn_cancel_manifest")
                btnSave = dialogView.findView("btn_save_manifest")
            } catch (t: Throwable) {
                Log.e("StreamPlayStremioAddonFrag", "Missing dialog views $t")
                return@OnClickListener
            }

            etName.background = StreamPlayDesignTokens.inputFieldDrawable(resources)
            etLink.background = StreamPlayDesignTokens.inputFieldDrawable(resources)
            rg.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
            rg.removeAllViews()

            val padH = StreamPlayDesignTokens.dpToPx(density, 14f)
            val padV = StreamPlayDesignTokens.dpToPx(density, 10f)
            val options = listOf(
                StreamPlayStremioAddonType.HTTPS to "HTTPS",
                StreamPlayStremioAddonType.TORRENT to "Torrent",
                StreamPlayStremioAddonType.SUBTITLE to "Subtitle",
                StreamPlayStremioAddonType.DEBRID to "Debrid"
            )
            options.forEachIndexed { index, (type, label) ->
                val pos = when (index) {
                    0 -> StreamPlayDesignTokens.GroupPosition.TOP
                    options.lastIndex -> StreamPlayDesignTokens.GroupPosition.BOTTOM
                    else -> StreamPlayDesignTokens.GroupPosition.MIDDLE
                }
                rg.addView(
                    RadioButton(requireContext()).apply {
                        id = View.generateViewId()
                        text = label
                        tag = type.name
                        textSize = 14f
                        minHeight = StreamPlayDesignTokens.dp(resources, StreamPlayDesignTokens.MIN_TOUCH_TARGET_DP)
                        setPadding(padH, padV, padH, padV)
                        setTextColor(StreamPlayDesignTokens.TEXT_PRIMARY)
                        background = StreamPlayDesignTokens.groupedRowDrawable(density, pos)
                        CompoundButtonCompat.setButtonTintList(
                            this,
                            StreamPlayDesignTokens.compoundButtonTintList()
                        )
                        isChecked = index == 0
                    }
                )
            }

            StreamPlayDesignTokens.styleButton(btnCancel, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
            StreamPlayDesignTokens.styleButton(btnSave, density, StreamPlayDesignTokens.ButtonVariant.PRIMARY)
            btnSave.text = "Save manifest"

            val dlg = StreamPlayDesignTokens.createThemedCustomDialog(requireContext(), resources, dialogView)
            btnCancel.setOnClickListener { dlg.dismiss() }
            btnSave.setOnClickListener {
                val name = etName.text.toString().trim()
                val link = etLink.text.toString().trim()
                val type = rg.findViewById<RadioButton>(rg.checkedRadioButtonId)?.tag?.toString()
                    ?: StreamPlayStremioAddonType.HTTPS.name

                if (link.isEmpty()) {
                    showToast("Enter a manifest URL")
                    return@setOnClickListener
                }

                val valid = try {
                    val scheme = link.toUri().scheme?.lowercase()
                    scheme == "http" || scheme == "https" || scheme == "stremio"
                } catch (_: Exception) {
                    false
                }

                if (!valid) {
                    showToast("Enter a valid URL (http, https, or stremio)")
                    return@setOnClickListener
                }

                try {
                    val list = loadLinks().toMutableList()
                    list.add(0, LinkItem(name = name.ifBlank { link }, link = link, type = type))
                    saveLinks(list)
                    refreshSavedCount()
                    Toast.makeText(requireContext(), "Addon manifest saved", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                } catch (e: Throwable) {
                    Log.e("StreamPlayStremioAddonFrag", "Failed to save link $e")
                    showToast("Failed to save addon manifest")
                }
            }

            dlg.show()
        }

        val openListDialog = View.OnClickListener {
            val dialogView = getLayout("stremio_dialog_list_links", inflater, container)
            val dlg = StreamPlayDesignTokens.createThemedCustomDialog(requireContext(), resources, dialogView)

            val rv: RecyclerView = dialogView.findView("rvLinks")
            val tvNoLinks: TextView = dialogView.findView("tvNoLinks")
            val btnClose: Button? = dialogView.findViewOrNull("btn_close_links")
            if (btnClose != null) {
                StreamPlayDesignTokens.styleButton(btnClose, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
                btnClose.setOnClickListener { dlg.dismiss() }
            }

            rv.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
            val list = loadLinks().toMutableList()

            if (list.isEmpty()) {
                tvNoLinks.visibility = View.VISIBLE
                rv.visibility = View.GONE
            } else {
                tvNoLinks.visibility = View.GONE
                rv.visibility = View.VISIBLE
                rv.layoutManager = LinearLayoutManager(requireContext())
                rv.adapter = LinksAdapter(list) { itemToDelete ->
                    val backupList = loadLinks().toList()
                    val updatedList = backupList.toMutableList()
                    val removed = updatedList.removeAll { it.id == itemToDelete.id }

                    if (removed) {
                        saveLinks(updatedList)
                        refreshSavedCount()
                        (rv.adapter as? LinksAdapter)?.remove(itemToDelete)
                        if (updatedList.isEmpty()) {
                            tvNoLinks.visibility = View.VISIBLE
                            rv.visibility = View.GONE
                        }
                        StreamPlayDesignTokens.showConfirmationDialog(
                            context = requireContext(),
                            resources = resources,
                            title = "Manifest removed",
                            message = "Removed \"${itemToDelete.name}\". Undo?",
                            confirmText = "Undo",
                            cancelText = "Keep removed",
                            confirmVariant = StreamPlayDesignTokens.ButtonVariant.PRIMARY
                        ) {
                            saveLinks(backupList)
                            refreshSavedCount()
                            list.clear()
                            list.addAll(backupList)
                            rv.adapter?.notifyDataSetChanged()
                            tvNoLinks.visibility = View.GONE
                            rv.visibility = View.VISIBLE
                        }
                    }
                }
            }
            dlg.show()
        }

        rowAddLink.setOnClickListener(openAddDialog)
        rowShowLinks.setOnClickListener(openListDialog)

        saveIcon.setOnClickListener {
            val ctx = this.context ?: return@setOnClickListener
            StreamPlayDesignTokens.showConfirmationDialog(
                context = ctx,
                resources = resources,
                title = "Restart to apply addons",
                message = "Saved addon manifests take effect after restarting CloudStream. Restart now?",
                confirmText = "Restart app",
                cancelText = "Later",
                confirmVariant = StreamPlayDesignTokens.ButtonVariant.PRIMARY
            ) {
                dismiss()
                StreamPlayDesignTokens.restartHostApp(requireContext())
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StreamPlayDesignTokens.applyWindowInsetsPadding(view)
    }

    data class LinkItem(
        val id: Long = System.currentTimeMillis(),
        val name: String,
        val link: String,
        val type: String
    )

    private fun loadLinks(): MutableList<LinkItem> {
        val json = sharedPref.getString(PREF_KEY_LINKS, null) ?: return mutableListOf()
        val list = mutableListOf<LinkItem>()

        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LinkItem(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", ""),
                        link = obj.optString("link", ""),
                        type = obj.optString("type", StreamPlayStremioAddonType.HTTPS.name)
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveLinks(list: List<LinkItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("link", item.link)
                put("type", item.type)
            }
            arr.put(obj)
        }
        sharedPref.edit { putString(PREF_KEY_LINKS, arr.toString()) }
    }

    inner class LinksAdapter(
        private val items: MutableList<LinkItem>,
        private val onDelete: (LinkItem) -> Unit
    ) : RecyclerView.Adapter<LinksAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findView("tvName")
            val tvLink: TextView = v.findView("tvLink")
            val tvType: TextView = v.findView("tvType")
            val btnDelete: ImageButton = v.findView("btnDelete")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layoutId = res.getIdentifier("stremio_item_saved_link", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
            val v = layoutInflater.inflate(res.getLayout(layoutId), parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val density = resources.displayMetrics.density
            val pos = when {
                items.size <= 1 -> StreamPlayDesignTokens.GroupPosition.SINGLE
                position == 0 -> StreamPlayDesignTokens.GroupPosition.TOP
                position == items.lastIndex -> StreamPlayDesignTokens.GroupPosition.BOTTOM
                else -> StreamPlayDesignTokens.GroupPosition.MIDDLE
            }
            holder.itemView.background = StreamPlayDesignTokens.groupedRowDrawable(density, pos)
            holder.tvName.text = item.name
            holder.tvLink.text = item.link
            holder.tvType.visibility = View.VISIBLE
            holder.tvType.text = formatAddonTypeLabel(item.type)
            holder.tvType.setTextColor(StreamPlayDesignTokens.TEXT_SECONDARY)
            runCatching {
                holder.btnDelete.setImageDrawable(getDrawable("delete_icon"))
            }
            holder.btnDelete.background = StreamPlayDesignTokens.iconButtonDrawable(resources)
            holder.btnDelete.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onDelete(item)
            }
        }

        override fun getItemCount(): Int = items.size

        fun remove(item: LinkItem) {
            val idx = items.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                items.removeAt(idx)
                notifyDataSetChanged()
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        StreamPlayDesignTokens.notifyParentSettingsChanged(this, onDismissCallback)
    }
}
