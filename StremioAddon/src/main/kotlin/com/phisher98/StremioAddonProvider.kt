package com.phisher98

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.phisher98.settings.SettingsFragment

@CloudstreamPlugin
class StremioAddonProvider: Plugin() {
    override fun load(context: Context) {
        NetworkOptimizer.initialize(context)
        DeviceProfiler.initialize(context)
        val sharedPref = context.getSharedPreferences("StremioAddon", Context.MODE_PRIVATE)
        try {
            ProviderTelemetryManager.loadPersistedStats(sharedPref)
        } catch (_: Exception) {}
        registerMainAPI(StremioAddon(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
