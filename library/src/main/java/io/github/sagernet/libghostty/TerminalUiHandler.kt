package io.github.sagernet.libghostty

import android.content.Context

/**
 * The default implementations show no UI: an unsafe paste is dropped, a clipboard read request
 * is denied, and a link is opened without a menu.
 */
public interface TerminalUiHandler {

    public fun confirmUnsafePaste(context: Context, paste: () -> Unit) {}

    /** [respond] must be called exactly once. */
    public fun requestClipboardRead(context: Context, respond: (approved: Boolean) -> Unit) {
        respond(false)
    }

    public fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
        openLink()
    }
}
