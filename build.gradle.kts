import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        project.extensions.findByType(JavaPluginExtension::class.java)?.apply {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/csprofesor/nik-cloudstream")
        authors = listOf("gsrepo")
    }

    android {
        namespace = "com.nikyokki"
        compileSdk = 36
        defaultConfig { minSdk = 21 }
        lint { targetSdk = 36 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        add("implementation", kotlin("stdlib"))
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
        add("implementation", "org.jsoup:jsoup:1.22.2")
        add("implementation", "org.jspecify:jspecify:1.0.0")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:2.13.1")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        add("implementation", "org.mozilla:rhino:1.8.1")
        add("implementation", "me.xdrop:fuzzywuzzy:1.4.0")
        add("implementation", "com.google.code.gson:gson:2.14.0")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        add("implementation", "org.bouncycastle:bcpkix-jdk18on:1.84")
    }
}

tasks.named("clean") {
    delete(rootProject.layout.buildDirectory)
}
repositories {
    mavenCentral()
}
dependencies {
    testImplementation(kotlin("test"))
}