package io.github.sagernet.libghostty.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sagernet.libghostty.GhosttyUiHandler
import io.github.sagernet.libghostty.R

@Stable
public class GhosttyDialogState : GhosttyUiHandler {

    internal sealed interface Request {
        class UnsafePaste(val paste: () -> Unit) : Request

        class ClipboardRead(val respond: (approved: Boolean) -> Unit) : Request

        class LinkMenu(val url: String, val openLink: () -> Unit, val copyUrl: () -> Unit) : Request
    }

    internal var request: Request? by mutableStateOf(null)

    override fun confirmUnsafePaste(context: Context, paste: () -> Unit) {
        request = Request.UnsafePaste(paste)
    }

    override fun requestClipboardRead(context: Context, respond: (approved: Boolean) -> Unit) {
        request = Request.ClipboardRead(respond)
    }

    override fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
        request = Request.LinkMenu(url, openLink, copyUrl)
    }
}

@Composable
public fun rememberGhosttyDialogState(): GhosttyDialogState = remember { GhosttyDialogState() }

@Composable
public fun GhosttyDialogs(state: GhosttyDialogState) {
    when (val request = state.request) {
        null -> {}

        is GhosttyDialogState.Request.UnsafePaste -> AlertDialog(
            onDismissRequest = { state.request = null },
            title = { Text(stringResource(R.string.ghostty_paste_unsafe_title)) },
            text = { Text(stringResource(R.string.ghostty_paste_unsafe_message)) },
            confirmButton = {
                TextButton(onClick = {
                    state.request = null
                    request.paste()
                }) {
                    Text(stringResource(android.R.string.paste))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.request = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is GhosttyDialogState.Request.ClipboardRead -> AlertDialog(
            onDismissRequest = {
                state.request = null
                request.respond(false)
            },
            title = { Text(stringResource(R.string.ghostty_clipboard_read_title)) },
            text = { Text(stringResource(R.string.ghostty_clipboard_read_message)) },
            confirmButton = {
                TextButton(onClick = {
                    state.request = null
                    request.respond(true)
                }) {
                    Text(stringResource(R.string.ghostty_clipboard_read_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.request = null
                    request.respond(false)
                }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is GhosttyDialogState.Request.LinkMenu -> AlertDialog(
            onDismissRequest = { state.request = null },
            title = { Text(request.url) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ghostty_open_link),
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.request = null
                                request.openLink()
                            }
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        stringResource(android.R.string.copyUrl),
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.request = null
                                request.copyUrl()
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { state.request = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
