package com.music.zenith

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BetaKillSwitch {

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

            // Never affect the production app.
            if (BuildConfig.APPLICATION_ID != "com.zerofuel.music.dev") {
                return@withContext Status(true, "")
            }

            val prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            try {
                val connection = URL(CONFIG_URL).openConnection()
                    as HttpURLConnection

                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                try {
                    val config = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

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

                    Status(enabled, message)
                } finally {
                    connection.disconnect()
                }

            } catch (_: Exception) {
                // Use the last successfully received status if offline.
                Status(
                    prefs.getBoolean(KEY_ENABLED, true),
                    prefs.getString(KEY_MESSAGE, "") ?: ""
                )
            }
        }
}