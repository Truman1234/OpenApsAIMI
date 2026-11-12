plugins {
    id("kmp-android-library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "app.aaps.pump.danars"
        androidResources { enable = true }
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:objects"))
                implementation(project(":core:utils"))
                implementation(project(":core:ui"))
                implementation(project(":pump:dana:common"))

                implementation(libs.kotlinx.datetime)
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.ui)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.jetbrains.lifecycle.runtime.compose)
            }
        }

        androidMain {
            // Everything of this module is Android only for now. Code moves to commonMain step by step.
            dependencies {
                implementation(project(":pump:dana:danars:emulator"))
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.lifecycle.runtime.compose)
            }
        }

        // Hand written rather than taken from test-module-dependencies, which applies
        // com.android.library and so cannot be used by a multiplatform module.
        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                implementation(kotlin("test"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.joda.time)
                implementation(libs.com.google.truth)
                implementation(libs.org.skyscreamer.jsonassert)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.org.json.android)
                runtimeOnly(libs.org.junit.vintage.engine)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}
