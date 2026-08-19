package io.github.sagernet.libghostty

import android.content.Context

public interface GhosttyUiHandler {

    public fun confirmUnsafePaste(context: Context, paste: () -> Unit) {}

    /** [respond] must be called exactly once. */
    public fun requestClipboardRead(context: Context, respond: (approved: Boolean) -> Unit) {
        respond(false)
    }

    public fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
        openLink()
    }
}
