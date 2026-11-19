package com.music.zenith

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ProductionKillSwitch {

    private const val TAG = "ZeroFuelProductionSwitch"

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/rohanDev0/zerofuel/main/production-config.json"

    suspend fun check(context: Context): BetaKillSwitch.Status =
        withContext(Dispatchers.IO) {

            // Never apply the production switch to beta builds.
            if (BuildConfig.APPLICATION_ID != "com.zerofuel.music") {
                return@withContext BetaKillSwitch.Status(true, "")
            }

            var connection: HttpURLConnection? = null

            try {
                val url = URL("$CONFIG_URL?nocache=${System.currentTimeMillis()}")

                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP error: ${connection.responseCode}")
                }

                val config = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(config)

                val enabled = json.optBoolean("production_enabled", true)

                val message = json.optString(
                    "production_message",
                    "ZeroFuel is temporarily unavailable."
                )

                Log.d(TAG, "Production enabled: $enabled")

                BetaKillSwitch.Status(enabled, message)

            } catch (e: Exception) {
                Log.e(TAG, "Production kill switch check failed", e)

                // Fail open: network errors must not lock out users.
                BetaKillSwitch.Status(true, "")

            } finally {
                connection?.disconnect()
            }
        }
}