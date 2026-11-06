package com.phisher98

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.phisher98.settings.SettingsFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperStreamPlugin: Plugin() {
    override fun load(context: Context) {
        NetworkOptimizer.initialize(context)
        DeviceProfiler.initialize(context)
        val sharedPref = context.getSharedPreferences("SuperStream", Context.MODE_PRIVATE)
        initializeSubtitleMode(context, sharedPref)
        try {
            ProviderTelemetryManager.loadPersistedStats(sharedPref)
        } catch (_: Exception) {}
        registerMainAPI(SuperStream(sharedPref))

        openSettings = { ctx ->
            val act = ctx.unwrapActivity()
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val frag = SettingsFragment(this, sharedPref)
                frag.show(act.supportFragmentManager, "Frag")
            } else {
                Log.e("SuperStreamPlugin", "Activity is not valid anymore, cannot show settings dialog")
            }
        }
    }

    private tailrec fun Context.unwrapActivity(): AppCompatActivity? = when (this) {
        is AppCompatActivity -> this
        is ContextWrapper -> baseContext.unwrapActivity()
        else -> null
    }
}