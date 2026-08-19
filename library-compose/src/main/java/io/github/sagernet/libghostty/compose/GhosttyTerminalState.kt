package io.github.sagernet.libghostty.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sagernet.libghostty.GhosttyExtraKeysState
import io.github.sagernet.libghostty.GhosttyTerminalView
import io.github.sagernet.libghostty.GhosttyUiHandler

@Stable
public class GhosttyTerminalState : GhosttyUiHandler {

    public val extraKeys: GhosttyExtraKeysState = GhosttyExtraKeysState()

    public var view: GhosttyTerminalView? by mutableStateOf(null)
        internal set

    internal sealed interface DialogRequest {
        class UnsafePaste(val paste: () -> Unit) : DialogRequest

        class ClipboardRead(val respond: (approved: Boolean) -> Unit) : DialogRequest

        class LinkMenu(val url: String, val openLink: () -> Unit, val copyUrl: () -> Unit) : DialogRequest
    }

    internal var dialogRequest: DialogRequest? by mutableStateOf(null)

    override fun confirmUnsafePaste(context: Context, paste: () -> Unit) {
        dialogRequest = DialogRequest.UnsafePaste(paste)
    }

    override fun requestClipboardRead(context: Context, respond: (approved: Boolean) -> Unit) {
        dialogRequest = DialogRequest.ClipboardRead(respond)
    }

    override fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
        dialogRequest = DialogRequest.LinkMenu(url, openLink, copyUrl)
    }
}

@Composable
public fun rememberGhosttyTerminalState(): GhosttyTerminalState = remember { GhosttyTerminalState() }
