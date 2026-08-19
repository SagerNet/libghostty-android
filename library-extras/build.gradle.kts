plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

val kotlinVersion = providers.gradleProperty("KOTLIN_VERSION").get()

android {
    namespace = "io.github.sagernet.libghostty.extras"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(layout.buildDirectory.dir("ghostty-assets").get().asFile.absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":library"))
    api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

apply<GhosttyThemesPlugin>()

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        project.property("GROUP") as String,
        project.property("POM_ARTIFACT_ID") as String,
        project.property("VERSION_NAME") as String,
    )
    pom {
        name.set("libghostty-android-extras")
        description.set("Theme catalog and font handling for libghostty-android")
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
