import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations

abstract class GhosttyThemesTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val themesDir: DirectoryProperty

    @TaskAction
    fun run() {
        val source = sourceDir.get().asFile
        val hash = Regex("""\.iterm2_themes\s*=\s*\.\{[^}]*\.hash\s*=\s*"([^"]+)"""")
            .find(File(source, "build.zig.zon").readText())
            ?.groupValues?.get(1)
            ?: throw GradleException("iterm2_themes hash not found in build.zig.zon")
        val packageDir = File(source, "zig-pkg/$hash")
        if (!packageDir.isDirectory) throw GradleException("$packageDir not found")
        val themes = themesDir.get().asFile
        fileSystemOperations.sync {
            from(packageDir)
            into(themes)
        }
        writeIndex(themes)
    }

    private fun writeIndex(themes: File) {
        val themeFiles = themes.listFiles { file -> file.isFile }?.sortedBy { it.name } ?: emptyList()
        // AGP asset merging strips dotfiles from the AAR and again from every
        // consumer's merged assets.
        File(themes, "index").writeText(
            themeFiles.joinToString("") { file ->
                val background = file.useLines { lines ->
                    lines.firstNotNullOfOrNull { line ->
                        val separator = line.indexOf('=')
                        if (separator > 0 && line.substring(0, separator).trim() == "background") {
                            line.substring(separator + 1).trim()
                        } else {
                            null
                        }
                    }
                }.orEmpty()
                "${file.name}\t$background\n"
            }
        )
    }
}

abstract class GhosttyTerminfoTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val terminfoDir: DirectoryProperty

    @TaskAction
    fun run() {
        val generator = File(temporaryDir, "generate-terminfo.zig")
        generator.writeText(
            """
            const std = @import("std");
            const terminfo = @import("ghostty_terminfo");

            pub fn main(init: std.process.Init) !void {
                var buffer: [4096]u8 = undefined;
                var stdout = std.Io.File.stdout().writerStreaming(init.io, &buffer);
                try terminfo.ghostty.encode(&stdout.interface);
                try stdout.end();
            }
            """.trimIndent()
        )
        val sourceFile = File(temporaryDir, "ghostty.terminfo")
        sourceFile.outputStream().use { output ->
            execOperations.exec {
                workingDir = temporaryDir
                commandLine(
                    "zig", "run",
                    "--dep", "ghostty_terminfo",
                    "-Mroot=${generator.absolutePath}",
                    "-Mghostty_terminfo=${File(sourceDir.get().asFile, "src/terminfo/main.zig").absolutePath}",
                )
                standardOutput = output
            }
        }
        val database = File(temporaryDir, "database")
        fileSystemOperations.delete { delete(database) }
        execOperations.exec {
            commandLine("tic", "-x", "-o", database.absolutePath, sourceFile.absolutePath)
        }
        // tic names the first-letter subdirectory "x" on Linux and the hex
        // form "78" on case-insensitive filesystems.
        val compiled = database.walkTopDown().firstOrNull { it.isFile && it.name == "xterm-ghostty" }
            ?: throw GradleException("xterm-ghostty not found under $database")
        val terminfo = terminfoDir.get().asFile
        fileSystemOperations.delete { delete(terminfo) }
        terminfo.mkdirs()
        compiled.copyTo(File(terminfo, "xterm-ghostty"))
    }
}

class GhosttyAssetsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val env = GhosttyEnv(project)
        val ghosttyAssetsDir = project.layout.buildDirectory.dir("ghostty-assets")

        val ghosttyThemes = project.tasks.register<GhosttyThemesTask>("ghosttyThemes") {
            dependsOn(env.source)
            commit.set(env.commit)
            sourceDir.set(env.sourceDir)
            themesDir.set(ghosttyAssetsDir.map { it.dir("ghostty-themes") })
        }

        val ghosttyTerminfo = project.tasks.register<GhosttyTerminfoTask>("ghosttyTerminfo") {
            dependsOn(env.source)
            commit.set(env.commit)
            sourceDir.set(env.sourceDir)
            terminfoDir.set(ghosttyAssetsDir.map { it.dir("ghostty-terminfo") })
        }

        project.tasks.named("preBuild") {
            dependsOn(ghosttyThemes, ghosttyTerminfo)
        }
    }
}
