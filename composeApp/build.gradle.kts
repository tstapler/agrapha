import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm("desktop") {
        mainRun {
            mainClass = "com.meetingnotes.MainKt"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)

                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.test)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.cio)
                implementation(libs.whisper.jni)
                implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.test)
                implementation(libs.mockk)
                implementation(libs.ktor.client.mock)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

// ── Rust native bridge (all platforms via Cargo) ──────────────────────────────
// Single crate for all platforms:
//   Linux  → libagrapha_native.so  (PipeWire audio + X11/Wayland hotkeys)
//   macOS  → libagrapha_native.dylib (ScreenCaptureKit audio via objc2)
//
// Prerequisites:
//   All:   rustup (stable toolchain)
//   Linux: libpipewire-0.3-dev, libx11-xcb-dev
//   macOS: Xcode Command Line Tools (for linker + Apple SDK frameworks)
val os = OperatingSystem.current()
val isLinux = os.isLinux
val isMacOs = os.isMacOsX

val nativeLibName = when {
    isLinux -> "libagrapha_native.so"
    isMacOs -> "libagrapha_native.dylib"
    else    -> null
}

val buildAgraphaNative by tasks.registering(Exec::class) {
    description = "Build libagrapha_native via Cargo"
    group = "build"
    enabled = isLinux || isMacOs

    workingDir = rootProject.file("native/agrapha-native")
    commandLine("cargo", "build", "--release")

    inputs.dir(rootProject.file("native/agrapha-native/src"))
    inputs.file(rootProject.file("native/agrapha-native/Cargo.toml"))
    if (nativeLibName != null) {
        outputs.file(rootProject.file("native/agrapha-native/target/release/$nativeLibName"))
    }

    doLast {
        if (nativeLibName != null) {
            val src = rootProject.file("native/agrapha-native/target/release/$nativeLibName")
            val dst = project.file("src/desktopMain/resources/$nativeLibName")
            dst.parentFile.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }
}

tasks.named("desktopProcessResources") {
    if (isLinux || isMacOs) dependsOn(buildAgraphaNative)
}

val cleanAgraphaNative by tasks.registering(Exec::class) {
    enabled = isLinux || isMacOs
    workingDir = rootProject.file("native/agrapha-native")
    commandLine("cargo", "clean")
}

tasks.named("clean") {
    if (isLinux || isMacOs) dependsOn(cleanAgraphaNative)
}

// ── FluidAudio Swift bridge (macOS only via Swift Package Manager) ─────────────
//
// Prerequisites (macOS only):
//   - Swift toolchain (Xcode or swift.org toolchain)
//   - JAVA_HOME pointing to a JDK with include/jni.h
//
// Local development: built with ad-hoc codesign (`codesign -s -`).
// Distribution: sign with a Developer ID certificate via notarytool before release.
val fluidBridgeDir = project.file("native/FluidDiarizationBridge")
val fluidBridgeDylib = project.file("native/FluidDiarizationBridge/.build/release/libFluidDiarizationBridge.dylib")
val fluidBridgeResource = project.file("src/desktopMain/resources/libFluidDiarizationBridge.dylib")

val buildFluidDiarizationBridge by tasks.registering(Exec::class) {
    description = "Build libFluidDiarizationBridge.dylib via Swift Package Manager (macOS only)"
    group = "build"
    enabled = isMacOs

    val javaHome = System.getenv("JAVA_HOME")
        ?: "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"

    workingDir = fluidBridgeDir
    commandLine(
        "swift", "build", "-c", "release",
        "-Xcc", "-I$javaHome/include",
        "-Xcc", "-I$javaHome/include/darwin",
    )

    inputs.dir(project.file("native/FluidDiarizationBridge/Sources"))
    inputs.file(project.file("native/FluidDiarizationBridge/Package.swift"))
    outputs.file(fluidBridgeDylib)

    doLast {
        fluidBridgeResource.parentFile.mkdirs()
        fluidBridgeDylib.copyTo(fluidBridgeResource, overwrite = true)
        // Ad-hoc sign for local development — replace with Developer ID cert for release.
        exec { commandLine("codesign", "-f", "-s", "-", fluidBridgeResource.absolutePath) }
    }
}

val cleanFluidDiarizationBridge by tasks.registering(Delete::class) {
    enabled = isMacOs
    delete(fluidBridgeResource)
    delete(project.file("native/FluidDiarizationBridge/.build"))
}

tasks.named("desktopProcessResources") {
    if (isMacOs) dependsOn(buildFluidDiarizationBridge)
}

tasks.named("clean") {
    if (isMacOs) dependsOn(cleanFluidDiarizationBridge)
}

sqldelight {
    databases {
        create("MeetingDatabase") {
            packageName.set("com.meetingnotes.db")
            srcDirs("src/commonMain/sqldelight")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.meetingnotes.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Agrapha"
            packageVersion = "1.0.0"  // x-release-please-version
            description = "Local meeting transcription that fits your memory system"
            vendor = "Agrapha"
            copyright = "© 2026 Agrapha contributors"

            // jlink builds a minimal JVM — explicitly include modules stripped by default.
            // java.sql: required by SQLDelight's SQLiteDriver (java.sql.Connection/SQLException)
            // jdk.unsupported: required by kotlinx.coroutines Unsafe access on JVM
            modules("java.sql", "jdk.unsupported")

            macOS {
                bundleID = "com.agrapha.app"
                iconFile.set(project.file("src/desktopMain/resources/Agrapha.icns"))
                entitlementsFile.set(project.file("src/desktopMain/resources/macOS.entitlements"))
                runtimeEntitlementsFile.set(project.file("src/desktopMain/resources/macOS.entitlements"))
            }
        }

        buildTypes.release.proguard {
            isEnabled = false
        }
    }
}
