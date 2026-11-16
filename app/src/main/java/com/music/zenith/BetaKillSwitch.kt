package com.music.zenith

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BetaKillSwitch {

    private const val TAG = "ZeroFuelKillSwitch"

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/rohanDev0/zerofuel/beta/beta-config.json"

    private const val PREFS = "beta_kill_switch"
    private const val KEY_ENABLED = "beta_enabled"
    private const val KEY_MESSAGE = "beta_message"

    data class Status(
        val enabled: Boolean,
        val message: String
    )

    suspend fun check(context: Context): Status =
        withContext(Dispatchers.IO) {

            val appId = BuildConfig.APPLICATION_ID
            Log.d(TAG, "Running application ID: $appId")

            if (appId != "com.zerofuel.music.dev") {
                Log.d(TAG, "Production app detected. Kill switch skipped.")
                return@withContext Status(true, "")
            }

            val prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            var connection: HttpURLConnection? = null

            try {
                val url = URL("$CONFIG_URL?nocache=${System.currentTimeMillis()}")

                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")

                val responseCode = connection.responseCode

                Log.d(TAG, "GitHub HTTP response: $responseCode")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP error: $responseCode")
                }

                val config = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                Log.d(TAG, "Received config: $config")

                val json = JSONObject(config)

                val enabled = json.optBoolean("beta_enabled", true)

                val message = json.optString(
                    "beta_message",
                    "This beta version is currently unavailable."
                )

                prefs.edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .putString(KEY_MESSAGE, message)
                    .apply()

                Log.d(TAG, "Final beta enabled status: $enabled")

                Status(enabled, message)

            } catch (e: Exception) {

                Log.e(TAG, "Kill switch check failed", e)

                Status(
                    prefs.getBoolean(KEY_ENABLED, true),
                    prefs.getString(KEY_MESSAGE, "") ?: ""
                )

            } finally {
                connection?.disconnect()
            }
        }
}
