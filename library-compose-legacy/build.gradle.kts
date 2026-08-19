plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

val kotlinVersion = providers.gradleProperty("KOTLIN_VERSION").get()

android {
    namespace = "io.github.sagernet.libghostty.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../library-compose/src/main/AndroidManifest.xml")
            java.directories.add("../library-compose/src/main/java")
            res.directories.add("../library-compose/src/main/res")
        }
    }

    buildFeatures {
        compose = true
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

    // compose-bom 2025.01.00 is the last BOM line with minSdk 21.
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        project.property("GROUP") as String,
        project.property("POM_ARTIFACT_ID") as String,
        project.property("VERSION_NAME") as String,
    )
    pom {
        name.set("libghostty-android-compose-legacy")
        description.set("Compose wrapper and dialogs for libghostty-android, for minSdk 21 consumers")
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
    }
}
