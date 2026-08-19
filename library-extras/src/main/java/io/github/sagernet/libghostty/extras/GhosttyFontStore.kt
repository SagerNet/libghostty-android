package io.github.sagernet.libghostty.extras

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File

public data class GhosttyImportedFont(
    val name: String,
    val path: String,
)

public object GhosttyFontStore {

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    private fun fontsDir(context: Context): File = File(context.filesDir, "fonts").also { it.mkdirs() }

    public fun listImportedFonts(context: Context): List<GhosttyImportedFont> = fontsDir(context).listFiles()
        ?.filter { it.extension in FONT_EXTENSIONS }
        ?.mapNotNull { file ->
            loadTypeface(file.absolutePath)
                ?.let { GhosttyImportedFont(name = file.nameWithoutExtension, path = file.absolutePath) }
        }
        ?.sortedBy { it.name }
        ?: emptyList()

    public fun importFont(context: Context, uri: Uri): GhosttyImportedFont? {
        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(uri) ?: return null
        val fileName = uri.lastPathSegment
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "font.ttf"
        val destFile = File(fontsDir(context), fileName)
        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (loadTypeface(destFile.absolutePath) == null) {
            destFile.delete()
            return null
        }
        return GhosttyImportedFont(name = destFile.nameWithoutExtension, path = destFile.absolutePath)
    }

    public fun deleteFont(context: Context, name: String) {
        val dir = fontsDir(context)
        dir.listFiles()
            ?.filter { it.nameWithoutExtension == name }
            ?.forEach { it.delete() }
    }

    public fun loadTypeface(path: String): Typeface? = try {
        Typeface.createFromFile(path)
    } catch (_: Exception) {
        null
    }

    public fun resolveTypeface(fontFamily: String, customFontPath: String): Typeface? {
        if (customFontPath.isNotBlank()) {
            val typeface = loadTypeface(customFontPath)
            if (typeface != null) return typeface
        }
        if (fontFamily.isNotBlank()) {
            return Typeface.create(fontFamily, Typeface.NORMAL)
        }
        return null
    }
}
