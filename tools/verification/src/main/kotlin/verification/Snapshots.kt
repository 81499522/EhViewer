package verification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

fun snapshotPlan(metadata: Map<String, Any?>): List<Pair<ApkInput, String>> {
    val inputs = apkInputs(metadata, "moe.tarsin.ehviewer")
    val version = inputs.first().version
    require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-SNAPSHOT").matches(version)) { "Expected a CI SNAPSHOT version" }
    val base = version.removeSuffix("-SNAPSHOT")
    return inputs.map { it to "EhViewer-$base-${it.abi}.apk" }
}

fun prepareSnapshot(apks: Path, tools: AndroidTools, output: Path, testSigning: Boolean, privateRoot: Path?): Map<String, Any?> {
    val plan = snapshotPlan(readJson(apks.resolve("output-metadata.json")))
    verifyApks(apks, tools, release = !testSigning)
    require(!testSigning || privateRoot == null) { "Test-signed outputs cannot be retained as production snapshots" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all", mergeError = false).isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "Snapshot source must be clean" }
    require(!output.exists()) { "Refusing to overwrite snapshot payload" }
    val manifest = mapOf(
        "source_commit" to source,
        "source_dirty" to dirty,
        "version" to plan.first().first.version,
        "versionCode" to plan.first().first.code,
        "production_signature_required" to !testSigning,
        "attachments" to plan.map { (input, name) -> mapOf("name" to name, "abi" to input.abi, "sha256" to sha256(apks.resolve(input.file))) },
    )
    if (privateRoot != null) {
        verifyDiagnostics(root.resolve("app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"), apks, tools)
        privateDirectory(privateRoot)
        val directory = privateRoot.resolve(source)
        privateDirectory(directory)
        val diagnostics = mapOf(
            "mapping.txt" to root.resolve("app/build/outputs/mapping/release/mapping.txt"),
            "native-debug-symbols.zip" to root.resolve("app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"),
        ).mapValues { (name, original) ->
            val retained = directory.resolve(name)
            retainFile(original, retained)
            mapOf("sha256" to sha256(retained), "bytes" to retained.fileSize())
        }
        retainFileManifest(directory.resolve("manifest.json"), manifest + mapOf("diagnostics" to diagnostics, "retention_days" to 90, "retain_until" to Instant.now().plus(90, ChronoUnit.DAYS).toString()))
    }
    output.createDirectories()
    for ((input, name) in plan) Files.copy(apks.resolve(input.file), output.resolve(input.abi).createDirectories().resolve(name))
    output.resolve("snapshot-manifest.json").writeText(json(manifest))
    return manifest
}

private fun retainFileManifest(path: Path, manifest: Map<String, Any?>) {
    Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions600)).writeText(json(manifest))
}
