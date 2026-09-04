package com.phisher98

// import com.phisher98.UltimaUtils.Provider

import com.phisher98.UltimaUtils.ExtensionInfo
import com.phisher98.UltimaUtils.MediaProviderState
import com.phisher98.UltimaUtils.SectionInfo
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object UltimaStorageManager {

    private fun <T : Any> getKeySafe(key: String, clazz: Class<T>): T? {
        return try {
            CloudStreamApp.getKeyClass(key, clazz)
        } catch (_: Throwable) {
            null
        }
    }

    // #region - custom data variables

    var extNameOnHome: Boolean
        get() = getKeySafe("ULTIMA_EXT_NAME_ON_HOME", Boolean::class.javaObjectType) ?: true
        set(value) {
            setKey("ULTIMA_EXT_NAME_ON_HOME", value)
        }

    @Volatile
    private var cachedExtensions: Array<ExtensionInfo>? = null

    @Volatile
    private var cachedMetaProviders: Array<Pair<String, Boolean>>? = null

    @Volatile
    private var cachedMediaProviders: Array<MediaProviderState>? = null

    private val storageLock = Any()

    fun invalidateCache() {
        synchronized(storageLock) {
            cachedExtensions = null
            cachedMetaProviders = null
            cachedMediaProviders = null
        }
    }

    internal fun setCachedExtensionsForTesting(extensions: Array<ExtensionInfo>?) {
        synchronized(storageLock) {
            cachedExtensions = extensions
        }
    }

    fun getEnabledPluginNames(): Set<String> {
        return currentExtensions
            .flatMap { it.sections?.asIterable() ?: emptyList() }
            .filter { it.enabled && it.pluginName.isNotBlank() }
            .map { it.pluginName }
            .toSet()
    }

    var currentExtensions: Array<ExtensionInfo>
        get() {
            cachedExtensions?.let { return it }
            return synchronized(storageLock) {
                cachedExtensions?.let { return it }
                val stored = getKeySafe("ULTIMA_EXTENSIONS_LIST", Array<ExtensionInfo>::class.java) ?: emptyArray()
                cachedExtensions = stored
                stored
            }
        }
        set(value) {
            setKey("ULTIMA_EXTENSIONS_LIST", value)
            invalidateCache()
        }

    var currentMetaProviders: Array<Pair<String, Boolean>>
        get() {
            cachedMetaProviders?.let { return it }
            return synchronized(storageLock) {
                cachedMetaProviders?.let { return it }
                val loaded = listMetaProviders()
                cachedMetaProviders = loaded
                loaded
            }
        }
        set(value) {
            synchronized(storageLock) {
                setKey("ULTIMA_CURRENT_META_PROVIDERS", value)
                cachedMetaProviders = null
            }
        }

    var currentMediaProviders: Array<MediaProviderState>
        get() {
            cachedMediaProviders?.let { return it }
            return synchronized(storageLock) {
                cachedMediaProviders?.let { return it }
                val loaded = listMediaProviders()
                cachedMediaProviders = loaded
                loaded
            }
        }
        set(value) {
            synchronized(storageLock) {
                setKey("ULTIMA_CURRENT_MEDIA_PROVIDERS", value)
                cachedMediaProviders = null
            }
        }


    var appSettingsSyncCreds: AppSettingsSyncCreds?
        get() = getKeySafe("ULTIMA_APP_SETTINGS_SYNC_CREDS", AppSettingsSyncCreds::class.java)
        set(value) {
            setKey("ULTIMA_APP_SETTINGS_SYNC_CREDS", value)
        }

    var lastLocalSyncTime: Long
        get() = getKeySafe("ULTIMA_LAST_LOCAL_SYNC_TIME", Long::class.javaObjectType) ?: 0L
        set(value) {
            setKey("ULTIMA_LAST_LOCAL_SYNC_TIME", value)
        }

    var syncV2Migrated: Boolean
        get() = getKeySafe("ULTIMA_SYNC_V2_MIGRATED", Boolean::class.javaObjectType) ?: false
        set(value) {
            setKey("ULTIMA_SYNC_V2_MIGRATED", value)
        }

    fun getCategoryTimestamp(category: SyncCategory): Long {
        return getKeySafe("ULTIMA_SYNC_TS_${category.key}", Long::class.javaObjectType) ?: 0L
    }

    fun setCategoryTimestamp(category: SyncCategory, ts: Long) {
        setKey("ULTIMA_SYNC_TS_${category.key}", ts)
    }

    fun getCategoryHash(category: SyncCategory): String {
        return getKeySafe("ULTIMA_SYNC_HASH_${category.key}", String::class.java) ?: ""
    }

    fun setCategoryHash(category: SyncCategory, hash: String) {
        setKey("ULTIMA_SYNC_HASH_${category.key}", hash)
    }

    fun getCategorySyncedKeys(category: SyncCategory): Set<String> {
        return getKeySafe("ULTIMA_SYNCED_KEYS_${category.key}", Array<String>::class.java)?.toSet() ?: emptySet()
    }

    fun setCategorySyncedKeys(category: SyncCategory, keys: Set<String>) {
        setKey("ULTIMA_SYNCED_KEYS_${category.key}", keys.toTypedArray())
    }

    // #endregion - custom data variables

    fun deleteAllData() {
        invalidateCache()
        listOf(
                        "ULTIMA_PROVIDER_LIST", // old key
                        "ULTIMA_EXT_NAME_ON_HOME",
                        "ULTIMA_EXTENSIONS_LIST",
                        "ULTIMA_CURRENT_META_PROVIDERS",
                        "ULTIMA_CURRENT_MEDIA_PROVIDERS",
                        "ULTIMA_APP_SETTINGS_SYNC_CREDS",
                        "ULTIMA_LAST_LOCAL_SYNC_TIME",
                        "ULTIMA_SYNC_V2_MIGRATED"
                )
                .forEach { setKey(it, null) }
        // Clear per-category sync state
        SyncCategory.entries.forEach { cat ->
            setKey("ULTIMA_SYNC_TS_${cat.key}", null)
            setKey("ULTIMA_SYNC_HASH_${cat.key}", null)
            setKey("ULTIMA_SYNCED_KEYS_${cat.key}", null)
        }
    }


    fun fetchExtensions(): Array<ExtensionInfo> {
        val providers = UltimaUtils.getAllProviders()
        return synchronized(providers) {
            val cachedExtensions = getKeySafe("ULTIMA_EXTENSIONS_LIST", Array<ExtensionInfo>::class.java)
            val filtered = providers.filter { it.name != "Ultima" }

            filtered.map { provider ->
                val existing = cachedExtensions?.find { it.name == provider.name }
                existing ?: ExtensionInfo(
                    name = provider.name,
                    provider.mainPage.map { section ->
                        SectionInfo(
                            name = section.name,
                            section.data,
                            provider.name,
                            false
                        )
                    }.toTypedArray()
                )
            }.toTypedArray()
        }
    }


    @Suppress("UNCHECKED_CAST")
    private fun listMetaProviders(): Array<Pair<String, Boolean>> {
        val currentProviders = UltimaMetaProviderUtils.metaProviders
        val storedProviders = getKeySafe(
            "ULTIMA_CURRENT_META_PROVIDERS",
            emptyArray<Pair<String, Boolean>>().javaClass
        ) ?: return currentProviders

        val currentNames = currentProviders.map { it.first }.sorted()
        val storedNames = storedProviders.map { it.first }.sorted()

        // If the names match (ignoring order), use the stored version
        if (currentNames == storedNames) return storedProviders

        // Merge stored flags if available, otherwise use default
        return currentProviders.map { provider ->
            storedProviders.find { it.first == provider.first } ?: provider
        }.toTypedArray()
    }


    private fun listMediaProviders(): Array<MediaProviderState> {
        val currentProviderNames = UltimaMediaProvidersUtils.mediaProviders.map { it.name }
        val stored = getKeySafe("ULTIMA_CURRENT_MEDIA_PROVIDERS", Array<MediaProviderState>::class.java)
            ?: return currentProviderNames.map { MediaProviderState(it, enabled = true, null) }.toTypedArray()

        val storedNames = stored.map { it.name }.sorted()
        if (currentProviderNames.sorted() == storedNames) {
            return stored.map { state ->
                MediaProviderState(
                    name = state.name,
                    enabled = state.enabled,
                    customDomain = state.customDomain,
                )
            }.toTypedArray()
        }

        return currentProviderNames.map { name ->
            val match = stored.find { it.name == name }
            MediaProviderState(
                name = name,
                enabled = match?.enabled ?: true,
                customDomain = match?.customDomain
            )
        }.toTypedArray()
    }

}
