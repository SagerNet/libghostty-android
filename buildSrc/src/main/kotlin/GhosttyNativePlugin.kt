import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
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

private data class GhosttyAbi(
    val abi: String,
    val zigTarget: String,
    val taskName: String,
)

class GhosttyNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val env = GhosttyEnv(project)
        val ghosttyCacheDir = env.buildDir.map { it.dir("zig-cache") }
        val ghosttyVtDir = project.layout.buildDirectory.dir("ghostty-vt")

        // Zig defaults the Android API level to 29 when the target omits it
        // (std.Target.Os.VersionRange.default).
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
