package io.github.sagernet.libghostty.extras

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

public object GhosttyTerminfo {

    private const val TAG = "GhosttyTerminfo"
    private const val TERMINFO_ASSET = "ghostty-terminfo/xterm-ghostty"

    public const val TERM: String = "xterm-ghostty"

    /**
     * Writes the compiled terminfo entry to `terminfoDir/x/xterm-ghostty`. The host points
     * `TERMINFO` (or the terminfo search path of its libc) at [terminfoDir] and sets
     * `TERM=xterm-ghostty`, and passes [TERM] as
     * [io.github.sagernet.libghostty.GhosttyTerminalSession.Options.terminfoName].
     */
    public fun install(context: Context, terminfoDir: File): Boolean = try {
        val data = context.assets.open(TERMINFO_ASSET).use { it.readBytes() }
        val target = File(terminfoDir, "x/$TERM")
        if (target.exists() && sha256(target.readBytes()).contentEquals(sha256(data))) {
            true
        } else {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$TERM.tmp")
            temp.writeBytes(data)
            temp.renameTo(target)
        }
    } catch (e: Exception) {
        Log.e(TAG, "install terminfo", e)
        false
    }

    public fun isInstalled(context: Context, terminfoDir: File): Boolean = try {
        val target = File(terminfoDir, "x/$TERM")
        target.exists() &&
            sha256(target.readBytes())
                .contentEquals(sha256(context.assets.open(TERMINFO_ASSET).use { it.readBytes() }))
    } catch (e: Exception) {
        false
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
