import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            packageVersion = "1.0.0"
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
