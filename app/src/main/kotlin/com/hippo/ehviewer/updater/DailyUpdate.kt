package com.hippo.ehviewer.updater

import tachiyomi.data.release.GithubCommitComparison
import tachiyomi.data.release.GithubRelease

internal suspend fun findDailyUpdate(
    currentSha: String,
    repository: String,
    releases: List<GithubRelease>,
    matches: (String) -> Boolean,
    compare: suspend (String, String) -> GithubCommitComparison,
): Release? {
    val release = releases.firstOrNull { it.isNightly } ?: return null
    if (release.targetCommitish.startsWith(currentSha)) return null
    val comparison = compare(currentSha, release.targetCommitish)
    // A delayed daily run must not offer a rollback or a divergent branch.
    if (comparison.status != "ahead") return null
    val changelog = comparison.commits.joinToString("\n") { commit ->
        "${commit.commit.message.takeWhile { it != '\n' }} (@${commit.commit.author.name})"
    }.ifEmpty { release.info.orEmpty() }
    return Release(release.version.removePrefix("v"), changelog, release.getDownloadLink(repository, matches))
}
