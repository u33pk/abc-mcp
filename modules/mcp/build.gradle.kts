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
        mainRun {
            mainClass.set("me.yricky.oh.mcp.MainKt")
        }
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

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat jar containing all runtime dependencies"
    dependsOn("jvmJar")

    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from({
        configurations["jvmRuntimeClasspath"].map { if (it.isDirectory) it else zipTree(it) }
    })
    from({
        tasks["jvmJar"].outputs.files.singleFile.let { if (it.isDirectory) it else zipTree(it) }
    })

    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")

    manifest {
        attributes["Main-Class"] = "me.yricky.oh.mcp.MainKt"
    }
}
