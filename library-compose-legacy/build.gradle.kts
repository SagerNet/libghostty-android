plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "io.github.sagernet.libghostty.compose"

    sourceSets {
        getByName("main") {
            kotlin.directories.add("../library-compose/src/main/java")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":library"))

    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}
