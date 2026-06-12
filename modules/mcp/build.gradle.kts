plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "me.yricky.oh"
version = rootProject.version

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        withJava()
    }
    
    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":modules:abcde"))
                implementation(project(":modules:resde"))
                implementation(project(":modules:hapde"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            }
        }
    }
}
