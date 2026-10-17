package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubWorkflowRuns(@SerialName("workflow_runs") val runs: List<GithubWorkflowRun>)

@Serializable
data class GithubWorkflowRun(
    val id: Long,
    @SerialName("head_sha") val sha: String,
    @SerialName("head_branch") val branch: String,
    val event: String,
    val status: String,
    val conclusion: String? = null,
    val path: String,
    @SerialName("head_repository") val repository: GithubRepository? = null,
) {
    fun isSnapshotSource(repositoryName: String): Boolean = id > 0 && Regex("[0-9a-f]{40}").matches(sha) &&
        branch == "main" && event == "push" && status == "completed" && conclusion == "success" &&
        path == ".github/workflows/ci.yml" && repository?.name == repositoryName
}

@Serializable
data class GithubRepository(@SerialName("full_name") val name: String)

@Serializable
data class GithubArtifacts(val artifacts: List<GithubArtifact>) {
    fun snapshotArtifact(sha: String, matches: (String) -> Boolean): GithubArtifact? {
        val required = listOf("snapshot-$sha", "snapshot-diagnostics-$sha") +
            listOf("arm64-v8a", "armeabi-v7a", "universal", "x86_64").map { "snapshot-$sha-$it" }
        val available = artifacts.filter { !it.expired && it.id > 0 && it.bytes > 0 }
        if (required.any { name -> available.count { it.name == name } != 1 }) return null
        val apks = available.filter { it.name in required.drop(2) }
        return apks.find { matches("${it.name}.apk") } ?: apks.single { it.name.endsWith("-universal") }
    }
}

@Serializable
data class GithubArtifact(val id: Long, val name: String, val expired: Boolean, @SerialName("size_in_bytes") val bytes: Long)
