package com.makkitv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Vidmoly

@CloudstreamPlugin
class MakkiTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MakkiTvProvider())
        registerExtractorAPI(Vidmolybiz())
    }
}

class Vidmolybiz : Vidmoly() {
    override val mainUrl = "https://vidmoly.biz"
}
