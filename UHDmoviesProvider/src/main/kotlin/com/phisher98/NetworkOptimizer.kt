package com.phisher98

import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NetworkOptimizer {
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        try {
            // Find client/okHttpClient field dynamically
            val field = try {
                app::class.java.getDeclaredField("client")
            } catch (_: Exception) {
                app::class.java.getDeclaredField("okHttpClient")
            }
            field.isAccessible = true
            val currentClient = field.get(app) as OkHttpClient
            
            val optimizedClient = currentClient.newBuilder()
                .connectionPool(ConnectionPool(30, 5, TimeUnit.MINUTES))
                .dns(DohDns)
                .build()
            
            field.set(app, optimizedClient)
            Log.d("NetworkOptimizer", "Successfully optimized OkHttpClient connection pool and set DoH DNS resolver.")
        } catch (e: Exception) {
            Log.e("NetworkOptimizer", "Failed to optimize OkHttpClient: ${e.message}")
        }
    }
}

object DohDns : Dns {
    private val systemDns = Dns.SYSTEM

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == "1.1.1.1" || hostname == "8.8.8.8" || hostname == "localhost") {
            return systemDns.lookup(hostname)
        }

        try {
            val result = queryDoh("https://1.1.1.1/dns-query?name=$hostname&type=A")
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            // Silently fall back to next resolver
        }

        try {
            val result = queryDoh("https://8.8.8.8/resolve?name=$hostname&type=A")
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            // Silently fall back to system
        }

        return systemDns.lookup(hostname)
    }

    private fun queryDoh(urlStr: String): List<InetAddress> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/dns-json")
        conn.connectTimeout = 1200
        conn.readTimeout = 1200

        if (conn.responseCode == 200) {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val answer = json.optJSONArray("Answer")
            if (answer != null && answer.length() > 0) {
                val list = mutableListOf<InetAddress>()
                for (i in 0 until answer.length()) {
                    val obj = answer.optJSONObject(i) ?: continue
                    val type = obj.optInt("type")
                    val data = obj.optString("data")
                    if (type == 1 && data.isNotEmpty()) {
                        list.addAll(InetAddress.getAllByName(data))
                    }
                }
                if (list.isNotEmpty()) return list
            }
        }
        return emptyList()
    }
}
