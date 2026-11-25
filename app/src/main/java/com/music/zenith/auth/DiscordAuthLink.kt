package com.music.zenith.auth

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DiscordAuthLink {

        private val _callbacks = MutableSharedFlow<Uri>(
                    extraBufferCapacity = 1
        )

            val callbacks = _callbacks.asSharedFlow()

                fun consume(intent: Intent?) {
                            val uri = intent?.data ?: return

                                    if (
                                                    intent.action == Intent.ACTION_VIEW &&
                                                                uri.scheme == "zerofuel" &&
                                                                            uri.host == "discord-auth"
                                    ) {
                                                    _callbacks.tryEmit(uri)
                                    }
                                }
                            }
                                    
                
        
