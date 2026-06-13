plugins {
    kotlin("multiplatform")
}

group = "me.yricky"
version = "1.0-SNAPSHOT"

repositories {
    maven("https://maven.aliyun.com/repository/central")
    maven("https://maven.aliyun.com/repository/public/")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

kotlin {
    jvm()
    jvmToolchain(26)

    sourceSets {
        jvmMain{
            dependencies {
                implementation(project(":modules:abcde"))
                implementation("com.google.code.gson:gson:2.8.9")
            }
        }

        jvmTest{
            dependencies {
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

    manifest {
        attributes["Main-Class"] = "me.yricky.oh.findstr.MainKt"
    }
}
