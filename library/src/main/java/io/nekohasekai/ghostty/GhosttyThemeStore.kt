package io.nekohasekai.ghostty

import android.content.Context
import android.graphics.Color
import android.util.Log

data class GhosttyTheme(
    val foreground: String?,
    val background: String?,
    val cursorColor: String?,
    val selectionBackground: String?,
    val selectionForeground: String?,
    val palette: Array<String?>,
)

object GhosttyThemeStore {

    private const val TAG = "GhosttyThemeStore"
    private const val THEME_INDEX_ASSET = "ghostty-themes/index"

    const val DEFAULT_LIGHT_THEME = "Alabaster"
    const val DEFAULT_DARK_THEME = "Afterglow"

    fun defaultTheme(isDark: Boolean): String = if (isDark) DEFAULT_DARK_THEME else DEFAULT_LIGHT_THEME

    fun listThemes(context: Context, isDark: Boolean): List<String> = readIndex(context).filter { (_, background) ->
        if (background.isEmpty()) {
            isDark
        } else {
            val luminance = backgroundLuminance(background)
            if (isDark) luminance <= 128 else luminance > 128
        }
    }.map { it.first }

    private fun readIndex(context: Context): List<Pair<String, String>> = try {
        context.assets.open(THEME_INDEX_ASSET).bufferedReader().readLines().mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator < 0) {
                null
            } else {
                line.substring(0, separator) to line.substring(separator + 1)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "read theme index", e)
        emptyList()
    }

    fun loadTheme(context: Context, name: String): GhosttyTheme? = try {
        var foreground: String? = null
        var background: String? = null
        var cursorColor: String? = null
        var selectionBackground: String? = null
        var selectionForeground: String? = null
        val palette = arrayOfNulls<String>(256)
        context.assets.open("ghostty-themes/$name").bufferedReader().forEachLine { line ->
            val separator = line.indexOf('=')
            if (separator < 0) return@forEachLine
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "palette" -> {
                    val entrySeparator = value.indexOf('=')
                    if (entrySeparator > 0) {
                        val index = value.substring(0, entrySeparator).trim().toIntOrNull()
                        if (index != null && index in 0..255) {
                            palette[index] = value.substring(entrySeparator + 1).trim()
                        }
                    }
                }
                "foreground" -> foreground = value
                "background" -> background = value
                "cursor-color" -> cursorColor = value
                "selection-background" -> selectionBackground = value
                "selection-foreground" -> selectionForeground = value
            }
        }
        GhosttyTheme(foreground, background, cursorColor, selectionBackground, selectionForeground, palette)
    } catch (e: Exception) {
        Log.e(TAG, "load theme $name", e)
        null
    }

    fun loadThemeOrDefault(context: Context, name: String, isDark: Boolean): GhosttyTheme? = loadTheme(context, name) ?: loadTheme(context, defaultTheme(isDark))

    private fun backgroundLuminance(color: String): Int {
        val parsed = GhosttyVt.nativeParseColor(color)
        return (0.299 * Color.red(parsed) + 0.587 * Color.green(parsed) + 0.114 * Color.blue(parsed)).toInt()
    }
}
