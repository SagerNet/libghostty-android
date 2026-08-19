plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "io.github.sagernet.libghostty"

    ndkVersion = "28.2.13676358"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DGHOSTTY_VT_DIR=${layout.buildDirectory.dir("ghostty-vt").get().asFile.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

apply<GhosttyNativePlugin>()

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        properties.set(mapOf("ghostty.commit" to rootProject.file("GHOSTTY_REF").readText().trim()))
    }
}
