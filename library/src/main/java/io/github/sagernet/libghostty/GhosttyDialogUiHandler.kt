package io.github.sagernet.libghostty

import android.app.AlertDialog
import android.content.Context

/** A [GhosttyUiHandler] that shows each request as an [AlertDialog]. */
public class GhosttyDialogUiHandler : GhosttyUiHandler {

    override fun confirmUnsafePaste(context: Context, paste: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.ghostty_paste_unsafe_title)
            .setMessage(R.string.ghostty_paste_unsafe_message)
            .setPositiveButton(android.R.string.paste) { _, _ -> paste() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun requestClipboardRead(context: Context, respond: (approved: Boolean) -> Unit) {
        var responded = false
        AlertDialog.Builder(context)
            .setTitle(R.string.ghostty_clipboard_read_title)
            .setMessage(R.string.ghostty_clipboard_read_message)
            .setPositiveButton(R.string.ghostty_clipboard_read_allow) { _, _ ->
                responded = true
                respond(true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                responded = true
                respond(false)
            }
            .setOnDismissListener {
                if (!responded) respond(false)
            }
            .show()
    }

    override fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(url)
            .setItems(
                arrayOf(
                    context.getString(R.string.ghostty_open_link),
                    context.getString(android.R.string.copyUrl),
                ),
            ) { _, which ->
                if (which == 0) openLink() else copyUrl()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
