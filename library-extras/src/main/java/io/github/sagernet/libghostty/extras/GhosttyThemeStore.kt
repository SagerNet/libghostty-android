package io.github.sagernet.libghostty.extras

import android.content.Context
import android.graphics.Color
import android.util.Log
import io.github.sagernet.libghostty.GhosttyColors
import io.github.sagernet.libghostty.GhosttyTheme

public object GhosttyThemeStore {

    private const val TAG = "GhosttyThemeStore"
    private const val THEME_INDEX_ASSET = "ghostty-themes/index"

    public const val DEFAULT_LIGHT_THEME: String = "Alabaster"
    public const val DEFAULT_DARK_THEME: String = "Afterglow"

    public fun defaultTheme(isDark: Boolean): String = if (isDark) DEFAULT_DARK_THEME else DEFAULT_LIGHT_THEME

    public fun listThemes(context: Context, isDark: Boolean): List<String> = readIndex(context).filter { (_, background) ->
        val luminance = background?.let(::backgroundLuminance)
        if (luminance == null) {
            isDark
        } else {
            if (isDark) luminance <= 128 else luminance > 128
        }
    }.map { it.first }

    private fun readIndex(context: Context): List<Pair<String, String?>> = try {
        context.assets.open(THEME_INDEX_ASSET).bufferedReader().readLines().mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator < 0) {
                null
            } else {
                line.substring(0, separator) to line.substring(separator + 1).takeIf { it.isNotEmpty() }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "read theme index", e)
        emptyList()
    }

    public fun loadTheme(context: Context, name: String): GhosttyTheme? = try {
        var foreground: Int? = null
        var background: Int? = null
        var cursorColor: Int? = null
        var selectionBackground: Int? = null
        var selectionForeground: Int? = null
        val palette = arrayOfNulls<Int>(256)
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
                            palette[index] = GhosttyColors.parse(value.substring(entrySeparator + 1).trim())
                        }
                    }
                }
                "foreground" -> foreground = GhosttyColors.parse(value)
                "background" -> background = GhosttyColors.parse(value)
                "cursor-color" -> cursorColor = GhosttyColors.parse(value)
                "selection-background" -> selectionBackground = GhosttyColors.parse(value)
                "selection-foreground" -> selectionForeground = GhosttyColors.parse(value)
            }
        }
        GhosttyTheme(foreground, background, cursorColor, selectionBackground, selectionForeground, palette.toList())
    } catch (e: Exception) {
        Log.e(TAG, "load theme $name", e)
        null
    }

    public fun loadThemeOrDefault(context: Context, name: String, isDark: Boolean): GhosttyTheme? = loadTheme(context, name) ?: loadTheme(context, defaultTheme(isDark))

    private fun backgroundLuminance(color: String): Int? {
        val parsed = GhosttyColors.parse(color) ?: return null
        return (0.299 * Color.red(parsed) + 0.587 * Color.green(parsed) + 0.114 * Color.blue(parsed)).toInt()
    }
}
