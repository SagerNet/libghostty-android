import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations

object GhosttyTools {
    fun onPath(name: String): Boolean =
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .any { File(it, name).canExecute() }

    fun require(name: String, purpose: String) {
        if (!onPath(name)) {
            throw GradleException("$name not found in PATH; it is required to $purpose")
        }
    }

    fun requireZig(purpose: String) = require("zig", "$purpose (https://ziglang.org/download/)")
}

abstract class GhosttySourceTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val repository: Property<String>

    @get:Input
    abstract val commit: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patches: ConfigurableFileCollection

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    init {
        outputs.upToDateWhen { (it as GhosttySourceTask).isCurrent() }
    }

    private fun isCurrent(): Boolean {
        val source = sourceDir.get().asFile
        if (!File(source, "build.zig").exists()) return false
        if (!GhosttyTools.onPath("git")) return false
        val head = ByteArrayOutputStream()
        val headResult = execOperations.exec {
            workingDir = source
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = head
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (headResult.exitValue != 0 || head.toString().trim() != commit.get()) return false
        val patchFiles = patches.files.sortedBy { it.name }
        if (patchFiles.isEmpty()) return true
        val reverseCheck = execOperations.exec {
            workingDir = source
            commandLine(
                listOf("git", "apply", "--reverse", "--check") + patchFiles.map { it.absolutePath }
            )
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return reverseCheck.exitValue == 0
    }

    @TaskAction
    fun run() {
        GhosttyTools.require("git", "fetch the ghostty source")
        val source = sourceDir.get().asFile
        if (!File(source, "build.zig").exists()) {
            source.deleteRecursively()
            source.mkdirs()
            git(source, "init", "-q")
            git(source, "remote", "add", "origin", repository.get())
        }
        git(source, "fetch", "--depth", "1", "origin", commit.get())
        git(source, "checkout", "-qf", commit.get())
        git(source, "clean", "-qfd")
        for (patch in patches.files.sortedBy { it.name }) {
            git(source, "apply", patch.absolutePath)
        }
    }

    private fun git(dir: File, vararg args: String) {
        execOperations.exec {
            workingDir = dir
            commandLine("git", *args)
        }
    }
}

abstract class GhosttyZigBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:Input
    abstract val zigTarget: Property<String>

    @get:Input
    abstract val commit: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patches: ConfigurableFileCollection

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val prefixDir: DirectoryProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val libDir: DirectoryProperty

    @get:Optional
    @get:OutputDirectory
    abstract val includeDir: DirectoryProperty

    @TaskAction
    fun run() {
        GhosttyTools.requireZig("build libghostty-vt")
        val prefix = prefixDir.get().asFile
        execOperations.exec {
            workingDir = sourceDir.get().asFile
            commandLine(
                "zig", "build", "-Demit-lib-vt", "-Dtarget=${zigTarget.get()}",
                "-Dsimd=false", "-Doptimize=ReleaseFast",
                "--cache-dir", cacheDir.get().asFile.absolutePath,
                "--prefix", prefix.absolutePath,
            )
        }
        val lib = libDir.get().asFile
        fileSystemOperations.delete { delete(lib) }
        fileSystemOperations.copy {
            from(File(prefix, "lib/libghostty-vt.a"))
            into(lib)
        }
        if (includeDir.isPresent) {
            val include = includeDir.get().asFile
            fileSystemOperations.delete { delete(include) }
            fileSystemOperations.copy {
                from(File(prefix, "include"))
                into(include)
            }
        }
    }
}

abstract class GhosttyThemesTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
    private val archiveOperations: ArchiveOperations,
) : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val themesDir: DirectoryProperty

    @TaskAction
    fun run() {
        GhosttyTools.requireZig("fetch the ghostty theme catalog")
        val zonLines = File(sourceDir.get().asFile, "build.zig.zon").readLines()
        val anchor = zonLines.indexOfFirst { it.contains("iterm2_themes") }
        val url = if (anchor < 0) {
            null
        } else {
            zonLines.subList(anchor, minOf(anchor + 3, zonLines.size))
                .firstNotNullOfOrNull { Regex("https://[^\"]*").find(it)?.value }
        } ?: throw GradleException("iterm2_themes URL not found in build.zig.zon")
        val fetchOutput = ByteArrayOutputStream()
        execOperations.exec {
            workingDir = sourceDir.get().asFile
            commandLine("zig", "fetch", url)
            standardOutput = fetchOutput
        }
        val hash = fetchOutput.toString().trim()
        val packageDir = File(zigGlobalCacheDir(), "p")
        val themes = themesDir.get().asFile
        fileSystemOperations.delete { delete(themes) }
        themes.mkdirs()
        val tarball = packageDir.listFiles { file -> file.name.startsWith("$hash.tar") }
            ?.minByOrNull { it.name }
        val bareEntry = File(packageDir, hash)
        when {
            tarball != null -> extract(tarball, themes)
            bareEntry.isDirectory -> fileSystemOperations.copy {
                from(bareEntry)
                into(themes)
            }
            bareEntry.isFile -> {
                val renamed = File(temporaryDir, "themes.tar.gz")
                bareEntry.copyTo(renamed, overwrite = true)
                extract(renamed, themes)
            }
            else -> throw GradleException("zig fetch output $hash not found under $packageDir")
        }
        writeIndex(themes)
    }

    private fun zigGlobalCacheDir(): File {
        val envOutput = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("zig", "env")
            standardOutput = envOutput
        }
        // zig env prints JSON up to zig 0.15 and ZON from 0.16
        // (global_cache_dir quoted and unquoted respectively).
        val globalCacheDir = Regex("\"?global_cache_dir\"?\\s*[=:]\\s*\"([^\"]+)\"")
            .find(envOutput.toString())
            ?.groupValues?.get(1)
            ?: throw GradleException("global_cache_dir not found in zig env output")
        return File(globalCacheDir)
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

    private fun extract(tarball: File, into: File) {
        fileSystemOperations.copy {
            from(archiveOperations.tarTree(tarball))
            into(into)
            eachFile {
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
            includeEmptyDirs = false
        }
    }
}

private data class GhosttyAbi(
    val abi: String,
    val zigTarget: String,
    val taskName: String,
)

private class GhosttyEnv(project: Project) {
    val commit: String = project.rootProject.file("GHOSTTY_REF").readText().trim()
    val patches = project.rootProject.layout.projectDirectory.dir("patches/ghostty")
        .asFileTree.matching {
            include("*.patch")
        }
    val buildDir = project.rootProject.layout.buildDirectory.dir("ghostty")
    val sourceDir = buildDir.map { it.dir("source") }

    init {
        if (!commit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("GHOSTTY_REF must contain a 40-character commit hash")
        }
    }

    val source = project.rootProject.tasks.let { tasks ->
        if (tasks.names.contains("ghosttySource")) {
            tasks.named("ghosttySource")
        } else {
            tasks.register<GhosttySourceTask>("ghosttySource") {
                repository.set("https://github.com/ghostty-org/ghostty.git")
                commit.set(this@GhosttyEnv.commit)
                patches.from(this@GhosttyEnv.patches)
                sourceDir.set(this@GhosttyEnv.sourceDir)
            }
        }
    }
}

class GhosttyNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val env = GhosttyEnv(project)
        val ghosttyCacheDir = env.buildDir.map { it.dir("zig-cache") }
        val ghosttyVtDir = project.layout.buildDirectory.dir("ghostty-vt")

        // Zig defaults the Android API level to 29 when the target omits it
        // (std.Target.Os.VersionRange.default); pin it to match minSdk.
        val ghosttyAbis = listOf(
            GhosttyAbi("arm64-v8a", "aarch64-linux-android.21", "ghosttyBuildArm64"),
            GhosttyAbi("armeabi-v7a", "arm-linux-androideabi.21", "ghosttyBuildArmv7"),
            GhosttyAbi("x86_64", "x86_64-linux-android.21", "ghosttyBuildX64"),
            GhosttyAbi("x86", "x86-linux-android.21", "ghosttyBuildX86"),
        )

        val ghosttyBuildTasks = ghosttyAbis.map { (abi, target, taskName) ->
            project.tasks.register<GhosttyZigBuildTask>(taskName) {
                dependsOn(env.source)
                zigTarget.set(target)
                commit.set(env.commit)
                patches.from(env.patches)
                sourceDir.set(env.sourceDir)
                prefixDir.set(env.buildDir.map { it.dir("out-$abi") })
                cacheDir.set(ghosttyCacheDir)
                libDir.set(ghosttyVtDir.map { it.dir(abi) })
                if (abi == "arm64-v8a") {
                    includeDir.set(ghosttyVtDir.map { it.dir("include") })
                }
            }
        }

        project.tasks.named("preBuild") {
            dependsOn(ghosttyBuildTasks)
        }
    }
}

class GhosttyThemesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val env = GhosttyEnv(project)
        val ghosttyAssetsDir = project.layout.buildDirectory.dir("ghostty-assets")

        val ghosttyThemes = project.tasks.register<GhosttyThemesTask>("ghosttyThemes") {
            dependsOn(env.source)
            commit.set(env.commit)
            sourceDir.set(env.sourceDir)
            themesDir.set(ghosttyAssetsDir.map { it.dir("ghostty-themes") })
        }

        project.tasks.named("preBuild") {
            dependsOn(ghosttyThemes)
        }
    }
}
