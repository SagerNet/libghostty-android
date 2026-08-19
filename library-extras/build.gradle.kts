plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "io.github.sagernet.libghostty.extras"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(layout.buildDirectory.dir("ghostty-assets").get().asFile.absolutePath)
        }
    }
}

dependencies {
    api(project(":library"))

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

apply<GhosttyThemesPlugin>()

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        properties.set(mapOf("ghostty.commit" to rootProject.file("GHOSTTY_REF").readText().trim()))
    }
}
