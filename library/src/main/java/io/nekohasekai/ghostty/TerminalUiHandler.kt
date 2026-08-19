package io.nekohasekai.ghostty

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

interface TerminalUiHandler {

    fun confirmUnsafePaste(context: Context, paste: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ghostty_paste_unsafe_title)
            .setMessage(R.string.ghostty_paste_unsafe_message)
            .setPositiveButton(android.R.string.paste) { _, _ -> paste() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** [respond] must be called exactly once. */
    fun requestClipboardRead(context: Context, respond: (approved: Boolean) -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ghostty_clipboard_read_title)
            .setMessage(R.string.ghostty_clipboard_read_message)
            .setPositiveButton(R.string.ghostty_clipboard_read_allow) { _, _ -> respond(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> respond(false) }
            .setOnCancelListener { respond(false) }
            .show()
    }

    fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(url)
            .setItems(
                arrayOf(
                    context.getString(R.string.ghostty_open_link),
                    context.getString(android.R.string.copyUrl),
                ),
            ) { _, which ->
                when (which) {
                    0 -> openLink()
                    1 -> copyUrl()
                }
            }
            .show()
    }
}
