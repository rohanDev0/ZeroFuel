package com.music.zenith.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

object DiscordAuthClient {

        private const val BASE_URL =
                "https://zerofuel-discord-auth.rohantemp9.workers.dev"

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

                                                                                context.getSharedPreferences(
                                                                                                PREFS,
                                                                                                            Context.MODE_PRIVATE
                                                                                ).edit()
                                                                                            .putString(VERIFIER, verifier)
                                                                                                        .putString(STATE, state)
                                                                                                                    .apply()

                                                                                                                            val authUrl = Uri.parse("$BASE_URL/auth/discord")
                                                                                                                                        .buildUpon()
                                                                                                                                                    .appendQueryParameter("code_challenge", challenge)
                                                                                                                                                                .appendQueryParameter("app_state", state)
                                                                                                                                                                            .build()

                                                                                                                                                                                    val intent = Intent(
                                                                                                                                                                                                    Intent.ACTION_VIEW,
                                                                                                                                                                                                                authUrl
                                                                                                                                                                                    )

                                                                                                                                                                                            context.startActivity(intent)
                                            }

                                                suspend fun exchange(
                                                            context: Context,
                                                                    callback: Uri
                                                ): JSONObject = withContext(Dispatchers.IO) {

                                                            val prefs = context.getSharedPreferences(
                                                                            PREFS,
                                                                                        Context.MODE_PRIVATE
                                                            )

                                                                    val verifier = prefs.getString(VERIFIER, null)
                                                                                ?: throw Exception("Missing PKCE verifier")

                                                                                        val savedState = prefs.getString(STATE, null)
                                                                                                    ?: throw Exception("Missing OAuth state")

                                                                                                            val code = callback.getQueryParameter("code")
                                                                                                                        ?: throw Exception("Missing authorization code")

                                                                                                                                val callbackState = callback.getQueryParameter("app_state")
                                                                                                                                            ?: throw Exception("Missing callback state")

                                                                                                                                                    if (callbackState != savedState) {
                                                                                                                                                                    throw Exception("OAuth state mismatch")
                                                                                                                                                    }

                                                                                                                                                            val body = JSONObject().apply {
                                                                                                                                                                            put("code", code)
                                                                                                                                                                                        put("appState", savedState)
                                                                                                                                                                                                    put("codeVerifier", verifier)
                                                                                                                                                            }

                                                                                                                                                                    val connection = URL(
                                                                                                                                                                                    "$BASE_URL/auth/exchange"
                                                                                                                                                                    ).openConnection() as HttpURLConnection

                                                                                                                                                                            try {
                                                                                                                                                                                            connection.requestMethod = "POST"
                                                                                                                                                                                                        connection.connectTimeout = 15000
                                                                                                                                                                                                                    connection.readTimeout = 15000
                                                                                                                                                                                                                                connection.doOutput = true

                                                                                                                                                                                                                                            connection.setRequestProperty(
                                                                                                                                                                                                                                                                "Content-Type",
                                                                                                                                                                                                                                                                                "application/json"
                                                                                                                                                                                                                                            )

                                                                                                                                                                                                                                                        connection.outputStream.use {
                                                                                                                                                                                                                                                                            it.write(body.toString().toByteArray(Charsets.UTF_8))
                                                                                                                                                                                                                                                        }

                                                                                                                                                                                                                                                                    val responseCode = connection.responseCode

                                                                                                                                                                                                                                                                                val stream = if (responseCode in 200..299) {
                                                                                                                                                                                                                                                                                                    connection.inputStream
                                                                                                                                                                                                                                                                                } else {
                                                                                                                                                                                                                                                                                                    connection.errorStream
                                                                                                                                                                                                                                                                                }

                                                                                                                                                                                                                                                                                            val response = stream.bufferedReader().use {
                                                                                                                                                                                                                                                                                                                it.readText()
                                                                                                                                                                                                                                                                                            }

                                                                                                                                                                                                                                                                                                        prefs.edit()
                                                                                                                                                                                                                                                                                                                        .remove(VERIFIER)
                                                                                                                                                                                                                                                                                                                                        .remove(STATE)
                                                                                                                                                                                                                                                                                                                                                        .apply()

                                                                                                                                                                                                                                                                                                                                                                    if (responseCode !in 200..299) {
                                                                                                                                                                                                                                                                                                                                                                                        throw Exception("Authentication failed: $response")
                                                                                                                                                                                                                                                                                                                                                                    }

                                                                                                                                                                                                                                                                                                                                                                                JSONObject(response)

                                                                                                                                                                                                                                                                                                                                                                } finally {
                                                                                                                                                                                                                                                                                                                                                                                connection.disconnect()
                                                                                                                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                                                                                                                                            }
                                                                                                                                                                                                                                                                                                                                                        }
                                                                                                                                                                                                                                                        
                                                                                                                                                    
                                                                
                                    
