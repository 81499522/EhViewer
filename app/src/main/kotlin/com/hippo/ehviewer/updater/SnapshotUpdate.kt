package com.hippo.ehviewer.updater

import tachiyomi.data.release.GithubArtifacts
import tachiyomi.data.release.GithubCommitComparison
import tachiyomi.data.release.GithubWorkflowRun

internal suspend fun findSnapshotUpdate(
    currentSha: String,
    repository: String,
    runs: List<GithubWorkflowRun>,
    matches: (String) -> Boolean,
    artifacts: suspend (Long) -> GithubArtifacts,
    compare: suspend (String, String) -> GithubCommitComparison,
): Release? {
    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository))
    require(repository.split('/').none { it == "." || it == ".." })
    require(Regex("[0-9a-f]{7,40}").matches(currentSha))
    var inspected = 0
    for (run in runs) {
        if (!run.isSnapshotSource(repository)) continue
        // Bound public API use when older successful runs predate snapshot artifacts.
        if (inspected++ == 10) break
        if (run.sha.startsWith(currentSha)) return null
        val artifact = artifacts(run.id).snapshotArtifact(run.sha, matches) ?: continue
        val comparison = compare(currentSha, run.sha)
        if (comparison.status != "ahead") return null
        val changelog = comparison.commits.joinToString("\n") { commit ->
            "${commit.commit.message.takeWhile { it != '\n' }} (@${commit.commit.author.name})"
        }
        return Release(
            "SNAPSHOT (${run.sha.take(7)})",
            changelog,
            "https://github.com/$repository/actions/runs/${run.id}/artifacts/${artifact.id}",
            UpdateDelivery.BROWSER,
        )
    }
    return null
}
