package com.music.zenith.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.music.zenith.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface DiscordExchangeResult {
    data class Success(
        val sessionToken: String,
        val username: String,
        /** Epoch millis at which the offline cache must stop being trusted. */
        val expiresAt: Long,
    ) : DiscordExchangeResult

    data object NotMember : DiscordExchangeResult
    data class Failure(val detail: String) : DiscordExchangeResult
}

sealed interface DiscordVerifyResult {
    data class Authorized(val username: String, val expiresAt: Long) : DiscordVerifyResult
    data class Unauthorized(val reason: String) : DiscordVerifyResult
    data object Unavailable : DiscordVerifyResult
}

object DiscordAuthClient {

    private const val PREFS = "zerofuel_discord_auth"
    private const val VERIFIER = "code_verifier"
    private const val STATE = "app_state"

    private val random = SecureRandom()

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun createChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))

        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun start(context: Context) {
        val verifier = randomToken()
        val challenge = createChallenge(verifier)
        val state = randomToken()

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(VERIFIER, verifier)
            .putString(STATE, state)
            .apply()

        val authUrl = Uri.parse("${BuildConfig.DISCORD_AUTH_BASE_URL}/auth/discord")
            .buildUpon()
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("app_state", state)
            .build()

        context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
    }

    suspend fun exchange(context: Context, callback: Uri): DiscordExchangeResult =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val verifier = prefs.getString(VERIFIER, null)
            val savedState = prefs.getString(STATE, null)
            var connection: HttpURLConnection? = null
            var consumePkce = false

            try {
                if (verifier.isNullOrBlank() || savedState.isNullOrBlank()) {
                    return@withContext DiscordExchangeResult.Failure("missing_pkce")
                }

                val callbackState = callback.getQueryParameter("app_state")
                    ?: return@withContext DiscordExchangeResult.Failure("missing_state")

                if (callbackState != savedState) {
                    return@withContext DiscordExchangeResult.Failure("state_mismatch")
                }

                // From here on the callback is ours: consume the one-time PKCE
                // material even if the exchange later fails. A forged URI with a
                // wrong state must not burn a login already in progress.
                consumePkce = true

                callback.getQueryParameter("error")?.let {
                    return@withContext DiscordExchangeResult.Failure("oauth_denied")
                }

                val code = callback.getQueryParameter("code")
                    ?: return@withContext DiscordExchangeResult.Failure("missing_code")

                val body = JSONObject().apply {
                    put("code", code)
                    put("appState", savedState)
                    put("codeVerifier", verifier)
                }

                connection = URL("${BuildConfig.DISCORD_AUTH_BASE_URL}/auth/exchange")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val json = try {
                    JSONObject(readBody(connection))
                } catch (_: Exception) {
                    return@withContext DiscordExchangeResult.Failure("bad_response")
                }

                if (responseCode !in 200..299) {
                    if (json.optString("reason") == "not_a_member" || responseCode == 403) {
                        return@withContext DiscordExchangeResult.NotMember
                    }
                    return@withContext DiscordExchangeResult.Failure(
                        json.optString("reason").takeIf { it.isNotBlank() }
                            ?: json.optString("error", "http_$responseCode")
                    )
                }

                if (!json.optBoolean("authorized", false)) {
                    if (json.optString("reason") == "not_a_member") {
                        return@withContext DiscordExchangeResult.NotMember
                    }
                    return@withContext DiscordExchangeResult.Failure("not_authorized")
                }

                val token = json.optString("sessionToken")
                if (token.isBlank()) {
                    return@withContext DiscordExchangeResult.Failure("missing_session")
                }

                DiscordExchangeResult.Success(
                    sessionToken = token,
                    username = json.optString("username"),
                    expiresAt = json.optLong("expiresAt", 0L),
                )
            } catch (_: Exception) {
                DiscordExchangeResult.Failure("network_error")
            } finally {
                if (consumePkce) {
                    prefs.edit()
                        .remove(VERIFIER)
                        .remove(STATE)
                        .apply()
                }
                connection?.disconnect()
            }
        }

    /**
     * Checks the worker, which now re-checks guild membership on every call.
     * A transport/Discord failure is deliberately distinct from a revocation:
     * only the former may fall back to a still-valid cached session.
     */
    suspend fun verifySession(token: String): DiscordVerifyResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null

            try {
                connection = URL("${BuildConfig.DISCORD_AUTH_BASE_URL}/api/verify")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Authorization", "Bearer $token")

                val responseCode = connection.responseCode
                val json = try {
                    JSONObject(readBody(connection))
                } catch (_: Exception) {
                    return@withContext DiscordVerifyResult.Unavailable
                }

                if (responseCode == 503) {
                    return@withContext DiscordVerifyResult.Unavailable
                }

                if (responseCode in 401..403) {
                    return@withContext DiscordVerifyResult.Unauthorized(reasonOf(json))
                }

                if (responseCode !in 200..299) {
                    return@withContext DiscordVerifyResult.Unavailable
                }

                if (!json.optBoolean("authorized", false)) {
                    return@withContext DiscordVerifyResult.Unauthorized(reasonOf(json))
                }

                val username = json.optJSONObject("user")?.optString("username")
                    ?.takeIf { it.isNotBlank() }
                    ?: json.optString("username")

                DiscordVerifyResult.Authorized(
                    username = username,
                    expiresAt = json.optLong("expiresAt", 0L),
                )
            } catch (_: Exception) {
                DiscordVerifyResult.Unavailable
            } finally {
                connection?.disconnect()
            }
        }

    private fun reasonOf(json: JSONObject): String =
        json.optString("reason").takeIf { it.isNotBlank() } ?: "invalid_session"

    private fun readBody(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }
}
