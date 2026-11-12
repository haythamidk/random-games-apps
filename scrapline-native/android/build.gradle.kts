plugins {
    id("com.android.application")
    kotlin("android")
}

val gdxVersion: String by project
val natives: Configuration by configurations.creating

android {
    namespace = "app.scrapline.desert"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.scrapline.desert"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
    }
    sourceSets["main"].apply {
        assets.srcDirs(rootProject.file("assets"))
        jniLibs.srcDirs("libs")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    for (abi in listOf("armeabi-v7a", "arm64-v8a", "x86_64")) {
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
        natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-$abi")
    }
}

// libGDX ships its native code as jars; unpack the .so files where Android looks for them.
val copyNatives by tasks.registering {
    val out = layout.projectDirectory.dir("libs")
    val files = natives
    inputs.files(files)
    outputs.dir(out)
    doLast {
        files.files.forEach { jar ->
            val abi = Regex("natives-(.+)\\.jar").find(jar.name)?.groupValues?.get(1) ?: return@forEach
            copy {
                from(zipTree(jar)) { include("*.so") }
                into(out.dir(abi))
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(copyNatives) }
