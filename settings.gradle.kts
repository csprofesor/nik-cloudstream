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
