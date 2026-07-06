package com.phisher98

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

enum class ServerList(val link: Pair<String, Boolean>) {
    RU("https://animepahe.ru" to true),
    PW("https://animepahe.pw" to false),
    ORG("https://animepahe.org" to true),
    COM("https://animepahe.com" to true);

    companion object {
        val defaultServer = RU.link.first
        private val legacyServers = setOf("https://animepahe.si")
        private val knownServers = entries.map { it.link.first }.toSet() + legacyServers

        fun normalize(value: String?): String {
            val trimmed = value?.trim()?.removeSuffix("/")
            return when {
                trimmed == null -> defaultServer
                trimmed !in knownServers -> defaultServer
                entries.firstOrNull { it.link.first == trimmed }?.link?.second == false -> defaultServer
                else -> trimmed
            }
        }
    }
}

@CloudstreamPlugin
class AnimePaheProviderPlugin: Plugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(AnimePahe())
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Pahe())

        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = BottomFragment(this)
            frag.show(activity.supportFragmentManager, "")
        }
    }

    companion object {
        var currentAnimepaheServer: String
            get() = ServerList.normalize(getKey("ANIMEPAHE_CURRENT_SERVER"))
            set(value) {
                setKey("ANIMEPAHE_CURRENT_SERVER", ServerList.normalize(value))
            }
    }
}
