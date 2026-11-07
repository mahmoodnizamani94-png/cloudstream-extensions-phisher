package com.phisher98

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.showToast

class StreamPlayStremioCatelogFrag() : DialogFragment() {

    private var pluginRef: StreamPlayPlugin? = null
    private var sharedPrefRef: SharedPreferences? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var activeSubDialog: Dialog? = null
    private var activeSubDialogType: String? = null
    private var draftCatalogName: String = ""
    private var draftCatalogLink: String = ""

    companion object {
        private const val STATE_SUB_DIALOG_TYPE = "state_catalog_sub_dialog_type"
        private const val STATE_DRAFT_NAME = "state_catalog_draft_name"
        private const val STATE_DRAFT_LINK = "state_catalog_draft_link"
        private const val SUB_DIALOG_ADD = "add"
        private const val SUB_DIALOG_LIST = "list"
        private const val SUB_DIALOG_RESTART = "restart"
    }

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

    private val PREF_KEY_LINKS = "streamplay_stremio_saved_links"
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
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun trackSubDialog(type: String, dialog: Dialog) {
        activeSubDialog?.dismiss()
        activeSubDialogType = type
        activeSubDialog = dialog
        dialog.setOnDismissListener {
            if (activeSubDialog === dialog) {
                activeSubDialog = null
                activeSubDialogType = null
            }
        }
    }

    @SuppressLint("DiscouragedApi", "SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = getLayout("stremio_bottom_sheet_layout", inflater, container)
        val density = resources.displayMetrics.density
        view.background = StreamPlayDesignTokens.dialogSurfaceDrawable(resources)

        val catalogActionsGroup: View? = view.findViewOrNull("catalog_actions_group")
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

        catalogActionsGroup?.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
        rowAddLink.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.TOP)
        rowShowLinks.background = StreamPlayDesignTokens.groupedRowDrawable(density, StreamPlayDesignTokens.GroupPosition.BOTTOM)
        saveIcon.background = StreamPlayDesignTokens.iconButtonDrawable(resources)

        fun refreshSavedCount() {
            val count = loadLinks().size
            savedLinksCountText.text = when (count) {
                0 -> "No catalog feeds saved"
                1 -> "1 catalog feed configured"
                else -> "$count catalog feeds configured"
            }
        }
        refreshSavedCount()

        fun showAddCatalogModal(initialName: String = "", initialLink: String = "") {
            val dialogView = getLayout("streamio_addon_addlinks", inflater, container)
            val etName: EditText
            val etLink: EditText
            val btnCancel: Button
            val btnSave: Button
            try {
                etName = dialogView.findView("etName")
                etLink = dialogView.findView("etLink")
                btnCancel = dialogView.findView("btn_cancel_manifest")
                btnSave = dialogView.findView("btn_save_manifest")
            } catch (t: Throwable) {
                Log.e("StreamPlayStremioCatelogFrag", "Missing dialog views $t")
                return
            }

            draftCatalogName = initialName
            draftCatalogLink = initialLink

            dialogView.findViewOrNull<TextView>("tvAddManifestTitle")?.text = "Add catalog manifest"
            dialogView.findViewOrNull<View>("label_addon_type")?.visibility = View.GONE
            dialogView.findViewOrNull<View>("radioGroup")?.visibility = View.GONE

            etName.background = StreamPlayDesignTokens.inputFieldDrawable(resources)
            etLink.background = StreamPlayDesignTokens.inputFieldDrawable(resources)
            if (initialName.isNotEmpty()) {
                etName.setText(initialName)
                etName.setSelection(initialName.length)
            }
            if (initialLink.isNotEmpty()) {
                etLink.setText(initialLink)
                etLink.setSelection(initialLink.length)
            }
            etName.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draftCatalogName = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            etLink.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draftCatalogLink = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })

            StreamPlayDesignTokens.styleButton(btnCancel, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
            StreamPlayDesignTokens.styleButton(btnSave, density, StreamPlayDesignTokens.ButtonVariant.PRIMARY)
            StreamPlayDesignTokens.applyDirectionalFocus(btnCancel, btnSave)
            btnSave.text = "Save catalog"

            val dlg = StreamPlayDesignTokens.createThemedCustomDialog(requireContext(), resources, dialogView)
            trackSubDialog(SUB_DIALOG_ADD, dlg)
            btnCancel.setOnClickListener { dlg.dismiss() }
            btnSave.setOnClickListener {
                val name = etName.text.toString().trim()
                val link = etLink.text.toString().trim()
                val type = "StremioC"

                if (link.isEmpty()) {
                    showToast("Enter a catalog manifest URL")
                    return@setOnClickListener
                }

                val valid = try {
                    val uri = link.toUri()
                    val scheme = uri.scheme?.lowercase()
                    scheme == "http" || scheme == "https"
                } catch (_: Exception) {
                    false
                }

                if (!valid) {
                    showToast("Enter a valid URL (http or https)")
                    return@setOnClickListener
                }

                val item = LinkItem(name = name.ifBlank { link }, link = link, type = type)
                try {
                    val list = loadLinks().toMutableList()
                    list.add(0, item)
                    saveLinks(list)
                    refreshSavedCount()
                    Toast.makeText(requireContext(), "Catalog manifest saved", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                } catch (e: Throwable) {
                    Log.e("StreamPlayStremioCatelogFrag", "Failed to save link $e")
                    showToast("Failed to save catalog manifest")
                }
            }

            dlg.show()
        }

        fun showSavedCatalogsModal() {
            val dialogView = getLayout("stremio_dialog_list_links", inflater, container)
            dialogView.findViewOrNull<TextView>("tvSavedManifestsTitle")?.text = "Saved catalog manifests"
            val dlg = StreamPlayDesignTokens.createThemedCustomDialog(requireContext(), resources, dialogView)
            trackSubDialog(SUB_DIALOG_LIST, dlg)

            val rv: RecyclerView = dialogView.findView("rvLinks")
            val tvNoLinks: TextView = dialogView.findView("tvNoLinks")
            val btnClose: Button? = dialogView.findViewOrNull("btn_close_links")
            if (btnClose != null) {
                StreamPlayDesignTokens.styleButton(btnClose, density, StreamPlayDesignTokens.ButtonVariant.SECONDARY)
                btnClose.setOnClickListener { dlg.dismiss() }
            }

            rv.background = StreamPlayDesignTokens.groupedSectionDrawable(density)
            StreamPlayDesignTokens.attachGroupedListDivider(rv, density)
            val list = loadLinks().toMutableList()

            if (list.isEmpty()) {
                tvNoLinks.visibility = View.VISIBLE
                rv.visibility = View.GONE
            } else {
                tvNoLinks.visibility = View.GONE
                rv.visibility = View.VISIBLE

                rv.layoutManager = LinearLayoutManager(requireContext())

                val adapter = LinksAdapter(list) { itemToDelete ->
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
                            title = "Catalog removed",
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

                rv.adapter = adapter
            }
            dlg.show()
        }

        fun showRestartConfirmationModal() {
            val ctx = this.context ?: return
            val dlg = StreamPlayDesignTokens.showConfirmationDialog(
                context = ctx,
                resources = resources,
                title = "Restart to apply catalogs",
                message = "Saved catalog feeds take effect after restarting CloudStream. Restart now?",
                confirmText = "Restart app",
                cancelText = "Later",
                confirmVariant = StreamPlayDesignTokens.ButtonVariant.PRIMARY
            ) {
                dismiss()
                StreamPlayDesignTokens.restartHostApp(requireContext())
            }
            trackSubDialog(SUB_DIALOG_RESTART, dlg)
        }

        rowAddLink.setOnClickListener { showAddCatalogModal() }
        rowShowLinks.setOnClickListener { showSavedCatalogsModal() }
        saveIcon.setOnClickListener { showRestartConfirmationModal() }

        when (savedInstanceState?.getString(STATE_SUB_DIALOG_TYPE)) {
            SUB_DIALOG_ADD -> showAddCatalogModal(
                initialName = savedInstanceState.getString(STATE_DRAFT_NAME, ""),
                initialLink = savedInstanceState.getString(STATE_DRAFT_LINK, "")
            )
            SUB_DIALOG_LIST -> showSavedCatalogsModal()
            SUB_DIALOG_RESTART -> showRestartConfirmationModal()
        }

        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (activeSubDialog?.isShowing == true && activeSubDialogType != null) {
            outState.putString(STATE_SUB_DIALOG_TYPE, activeSubDialogType)
            outState.putString(STATE_DRAFT_NAME, draftCatalogName)
            outState.putString(STATE_DRAFT_LINK, draftCatalogLink)
        }
    }

    override fun onDestroyView() {
        activeSubDialog?.setOnDismissListener(null)
        activeSubDialog?.dismiss()
        activeSubDialog = null
        super.onDestroyView()
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
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LinkItem(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", ""),
                        link = obj.optString("link", ""),
                        type = obj.optString("type", "StremioC")
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveLinks(list: List<LinkItem>) {
        val arr = org.json.JSONArray()
        for (item in list) {
            val obj = org.json.JSONObject().apply {
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
            // Do not repeat "Catalog" on every row of the catalog manifests list
            holder.tvType.visibility = View.GONE
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
