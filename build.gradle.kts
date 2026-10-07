plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.soywiz.korge") version "6.0.0"
}

kotlin {
    js(IR) {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.soywiz.korge:korge:6.0.0")
            }
        }
    }
}

korge {
    id = "com.serakib.skyharbor"
    name = "Sky Harbor"
    version = "1.0.0"
    orientation = "landscape"
}
