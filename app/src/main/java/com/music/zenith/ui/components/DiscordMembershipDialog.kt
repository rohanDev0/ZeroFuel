package com.music.zenith.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.zenith.R
import com.music.zenith.auth.MembershipGate

@Composable
fun DiscordMembershipDialog(
    state: MembershipGate.State,
    onJoin: () -> Unit,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
) {
    val body = when (state) {
        MembershipGate.State.Checking -> stringResource(R.string.membership_dialog_checking)
        MembershipGate.State.Unknown -> stringResource(R.string.membership_dialog_body)
        is MembershipGate.State.Verified -> stringResource(R.string.membership_dialog_verified)
        is MembershipGate.State.Blocked -> when (state.reason) {
            "not_a_member" -> stringResource(R.string.membership_dialog_not_member)
            "verification_unavailable" -> stringResource(R.string.membership_dialog_verification_unavailable)
            "auth_failed" -> stringResource(R.string.membership_dialog_auth_failed)
            else -> stringResource(R.string.membership_dialog_body)
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = stringResource(R.string.membership_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state == MembershipGate.State.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .size(24.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAuthorize,
                enabled = state !is MembershipGate.State.Checking,
            ) {
                Text(stringResource(R.string.membership_dialog_authorize))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onJoin) {
                    Text(stringResource(R.string.membership_dialog_join))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.membership_dialog_cancel))
                }
            }
        },
    )
}
