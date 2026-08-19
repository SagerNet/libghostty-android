import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "io.nekohasekai.ghostty"
    compileSdk = 37

    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Outputs of GhosttyBuildPlugin.
                arguments += "-DGHOSTTY_VT_DIR=${layout.buildDirectory.dir("ghostty-vt").get().asFile.absolutePath}"
            }
        }
    }

    sourceSets {
        getByName("main") {
            // Outputs of GhosttyBuildPlugin.
            assets.directories.add(layout.buildDirectory.dir("ghostty-assets").get().asFile.absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.android.material:material:1.14.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Builds libghostty-vt from source and installs the ghostty theme assets.
apply<GhosttyBuildPlugin>()

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        project.property("GROUP") as String,
        project.property("POM_ARTIFACT_ID") as String,
        project.property("VERSION_NAME") as String,
    )
    pom {
        name.set("libghostty-android")
        description.set("Android terminal view and libghostty-vt bindings")
        url.set("https://github.com/SagerNet/libghostty-android")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
            }
        }
        developers {
            developer {
                id.set("nekohasekai")
                name.set("nekohasekai")
            }
        }
        scm {
            url.set("https://github.com/SagerNet/libghostty-android")
            connection.set("scm:git:https://github.com/SagerNet/libghostty-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/SagerNet/libghostty-android.git")
        }
        properties.set(mapOf("ghostty.commit" to rootProject.file("GHOSTTY_REF").readText().trim()))
    }
}
