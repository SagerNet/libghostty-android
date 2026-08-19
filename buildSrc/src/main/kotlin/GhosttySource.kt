import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
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

internal class GhosttyEnv(project: Project) {
    val commit: String = project.rootProject.file("GHOSTTY_REF").readText().trim()
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
                repository.set("https://github.com/ghostty-org/ghostty.git")
                commit.set(this@GhosttyEnv.commit)
                patches.from(this@GhosttyEnv.patches)
                sourceDir.set(this@GhosttyEnv.sourceDir)
            }
        }
    }
}
