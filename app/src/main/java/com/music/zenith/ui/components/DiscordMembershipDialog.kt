package com.music.zenith.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun DiscordMembershipDialog(
    onJoin: () -> Unit,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Join our Discord")
        },
        text = {
            Text("Join the ZeroFuel Discord server and authorize your account to play music.")
        },
        confirmButton = {
            TextButton(onClick = onJoin) {
                Text("Join Discord")
            }
        },
        dismissButton = {
            TextButton(onClick = onAuthorize) {
                Text("Authorize")
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}