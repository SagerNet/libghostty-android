plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    // AGP 9.3.1 resolves its built-in Kotlin from these two configurations at 2.2.10; raising them
    // requires kotlin.compiler.runViaBuildToolsApi, which lifts the plugin/compiler version check.
    configurations.matching { it.name in setOf("kotlinCompilerClasspath", "kotlinBuildToolsApiClasspath") }
        .configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(providers.gradleProperty("KOTLIN_VERSION").get())
                }
            }
        }
}
