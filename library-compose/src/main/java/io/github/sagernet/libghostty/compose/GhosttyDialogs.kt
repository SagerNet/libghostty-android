package io.github.sagernet.libghostty.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sagernet.libghostty.R

@Composable
public fun GhosttyDialogs(state: GhosttyTerminalState) {
    when (val request = state.dialogRequest) {
        null -> {}

        is GhosttyTerminalState.DialogRequest.UnsafePaste -> AlertDialog(
            onDismissRequest = { state.dialogRequest = null },
            title = { Text(stringResource(R.string.ghostty_paste_unsafe_title)) },
            text = { Text(stringResource(R.string.ghostty_paste_unsafe_message)) },
            confirmButton = {
                TextButton(onClick = {
                    state.dialogRequest = null
                    request.paste()
                }) {
                    Text(stringResource(android.R.string.paste))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.dialogRequest = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is GhosttyTerminalState.DialogRequest.ClipboardRead -> AlertDialog(
            onDismissRequest = {
                state.dialogRequest = null
                request.respond(false)
            },
            title = { Text(stringResource(R.string.ghostty_clipboard_read_title)) },
            text = { Text(stringResource(R.string.ghostty_clipboard_read_message)) },
            confirmButton = {
                TextButton(onClick = {
                    state.dialogRequest = null
                    request.respond(true)
                }) {
                    Text(stringResource(R.string.ghostty_clipboard_read_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.dialogRequest = null
                    request.respond(false)
                }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is GhosttyTerminalState.DialogRequest.LinkMenu -> AlertDialog(
            onDismissRequest = { state.dialogRequest = null },
            title = { Text(request.url) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ghostty_open_link),
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.dialogRequest = null
                                request.openLink()
                            }
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        stringResource(android.R.string.copyUrl),
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.dialogRequest = null
                                request.copyUrl()
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { state.dialogRequest = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
