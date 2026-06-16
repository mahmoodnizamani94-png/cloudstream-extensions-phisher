package com.Coflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class darkibox : ExtractorApi() {
    override var name = "Darkibox"
    override var mainUrl = "https://darkibox.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
            val response = app.get(url).toString()
            Regex("""sources:\s*\[\{src:\s*"(.*?)"""").find(response)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = link,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        return null
    }
}

open class Videzz : ExtractorApi() {
    override var name = "Videzz"
    override var mainUrl = "https://videzz.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
            val mp4 = app.get(url,referer=mainUrl).document.select("#vplayer > #player source").attr("src")
            return listOf(
                newExtractorLink(
                    this.name,
                    this.name,
                    url = mp4,
                    type = INFER_TYPE
                ) {
                    this.referer = referer ?: ""
                    this.quality = Qualities.P1080.value
                }
            )
    }
}

class VidHideplus : VidhideExtractor() {
    override var mainUrl = "https://vidhideplus.com"
}


class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class wishonly : StreamWishExtractor() {
    override var mainUrl = "https://wishonly.site"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}

class CoflixUPN : VidStack() {
    override var mainUrl = "https://coflix.upn.one"
}

class Mivalyo : VidhideExtractor() {
    override var mainUrl = "https://mivalyo.com"
}


class Uqload : ExtractorApi() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.cx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val html = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
        val srcRegex = Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""")
        val videoUrl = srcRegex.find(html)?.groupValues?.get(1)
        if (videoUrl != null) {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    videoUrl,
                    INFER_TYPE
                )
                {
                    this.referer = referer ?: mainUrl
                }
            )
        }
    }
}