package com.music.zenith.auth

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The browser returns to `zerofuel://discord-auth`, which arrives either in
 * `onCreate` (cold launch) or `onNewIntent` (activity already alive). Both call
 * [consume], and the composition collects the same flow.
 */
object DiscordAuthLink {

    private val callbackChannel = Channel<Uri>(Channel.BUFFERED)
    val callbacks = callbackChannel.receiveAsFlow()

    fun consume(intent: Intent?) {
        val uri = intent?.data ?: return
        if (
            intent.action == Intent.ACTION_VIEW &&
            uri.scheme == "zerofuel" &&
            uri.host == "discord-auth"
        ) {
            callbackChannel.trySend(uri)
        }
    }
}
