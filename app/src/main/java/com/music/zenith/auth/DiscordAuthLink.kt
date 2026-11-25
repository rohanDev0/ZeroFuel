package com.music.zenith.auth

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

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
                                    
                                    
                
