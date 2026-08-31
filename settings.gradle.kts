pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.10"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "gsrepo"

// All plugin directories containing build.gradle.kts are included automatically.
// Only the shared template directory is excluded.
val disabled = listOf<String>("__Temel")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// Build trigger: gsrepo
