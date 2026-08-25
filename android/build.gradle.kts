plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muscab2006.gridlockheat"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.gridlockheat.qeytil"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.1-qeytil"
        // Fully offline game: INTERNET permission deliberately ABSENT.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("extracted-jniLibs"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // placeholder until release keys exist
        }
    }

    packaging {
        resources.excludes += listOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
}

configurations {
    maybeCreate("natives").apply { isCanBeResolved = true }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backend.android)
    implementation(libs.gdx)

    add("natives", "com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-armeabi-v7a")
    add("natives", "com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-arm64-v8a")
}

val copyAndroidNatives = tasks.register<Copy>("copyAndroidNatives") {
    from(configurations["natives"].map { f -> if (f.extension == "jar") zipTree(f) else f })
    into(layout.buildDirectory.dir("extracted-jniLibs"))
    include("armeabi-v7a/*", "arm64-v8a/*")
}

tasks.named("preBuild") { dependsOn(copyAndroidNatives) }
