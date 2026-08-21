import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations

abstract class GhosttySourceTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Internal
    abstract val repositories: ListProperty<String>

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
        val source = sourceDir.get().asFile
        if (!File(source, "build.zig").exists()) {
            source.deleteRecursively()
            source.mkdirs()
            git(source, "init", "-q")
        }
        fetch(source)
        git(source, "checkout", "-qf", commit.get())
        git(source, "clean", "-qfd")
        for (patch in patches.files.sortedBy { it.name }) {
            git(source, "apply", patch.absolutePath)
        }
    }

    private fun fetch(source: File) {
        val repositories = repositories.get()
        for ((index, repository) in repositories.withIndex()) {
            val last = index == repositories.lastIndex
            val result = execOperations.exec {
                workingDir = source
                commandLine("git", "fetch", "--depth", "1", repository, commit.get())
                if (!last) {
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            }
            if (result.exitValue == 0) return
        }
    }

    private fun git(dir: File, vararg args: String) {
        execOperations.exec {
            workingDir = dir
            commandLine("git", *args)
        }
    }
}

const val GHOSTTY_SUBMODULE = "ghostty"

fun ghosttyCommit(project: Project): String {
    val entry = project.providers.exec {
        workingDir = project.rootDir
        commandLine("git", "ls-files", "-s", GHOSTTY_SUBMODULE)
    }.standardOutput.asText.get().trim()
    return entry.split(Regex("\\s+")).getOrNull(1)
        ?: throw GradleException("$GHOSTTY_SUBMODULE is not registered as a submodule")
}

internal class GhosttyEnv(project: Project) {
    val commit: String = ghosttyCommit(project)
    val patches = project.rootProject.layout.projectDirectory.dir("patches/ghostty")
        .asFileTree.matching {
            include("*.patch")
        }
    val buildDir = project.rootProject.layout.buildDirectory.dir("ghostty")
    val sourceDir = buildDir.map { it.dir("source") }

    val source = project.rootProject.tasks.let { tasks ->
        if (tasks.names.contains("ghosttySource")) {
            tasks.named("ghosttySource")
        } else {
            tasks.register<GhosttySourceTask>("ghosttySource") {
                repositories.set(
                    listOf(
                        project.rootProject.file(GHOSTTY_SUBMODULE).absolutePath,
                        "https://github.com/ghostty-org/ghostty.git",
                    )
                )
                commit.set(this@GhosttyEnv.commit)
                patches.from(this@GhosttyEnv.patches)
                sourceDir.set(this@GhosttyEnv.sourceDir)
            }
        }
    }
}
