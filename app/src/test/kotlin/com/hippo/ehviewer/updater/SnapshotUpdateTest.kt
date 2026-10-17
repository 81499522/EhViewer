package com.hippo.ehviewer.updater

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.release.GithubArtifact
import tachiyomi.data.release.GithubArtifacts
import tachiyomi.data.release.GithubCommitComparison
import tachiyomi.data.release.GithubRepository
import tachiyomi.data.release.GithubWorkflowRun
import tachiyomi.data.release.GithubWorkflowRuns

class SnapshotUpdateTest {
    private val repo = "example/EhViewer"
    private val sha = "a".repeat(40)
    private fun run() = GithubWorkflowRun(42, sha, "main", "push", "completed", "success", ".github/workflows/ci.yml", GithubRepository(repo))
    private fun artifacts(): GithubArtifacts = GithubArtifacts(
        (listOf("snapshot-$sha-2-3-4-5-6", "snapshot-diagnostics-$sha") + listOf("arm64-v8a", "armeabi-v7a", "universal", "x86_64").map { "snapshot-$sha-$it" })
            .mapIndexed { index, name -> GithubArtifact(index + 1L, name, false, 100) },
    )

    @Test fun selectsOnlySuccessfulMainPushesFromThisWorkflowAndRepository() {
        val valid = run()
        assertTrue(valid.isSnapshotSource(repo))
        for (invalid in listOf(
            valid.copy(event = "pull_request"), valid.copy(event = "workflow_dispatch"), valid.copy(event = "schedule"),
            valid.copy(branch = "feature/test"), valid.copy(status = "in_progress"), valid.copy(conclusion = "failure"),
            valid.copy(path = ".github/workflows/other.yml"), valid.copy(sha = "main"), valid.copy(id = 0),
            valid.copy(repository = GithubRepository("other/EhViewer")), valid.copy(repository = null),
        )) {
            assertFalse(invalid.isSnapshotSource(repo))
        }
    }

    @Test fun requiresFourCompleteLiveApksAndTheirCompletionAndDiagnosticsMarkers() {
        val all = artifacts()
        assertEquals("snapshot-$sha-arm64-v8a", all.snapshotArtifact(sha) { it.endsWith("-arm64-v8a.apk") }?.name)
        assertEquals("snapshot-$sha-universal", all.snapshotArtifact(sha) { false }?.name)
        for (entry in all.artifacts) {
            assertEquals(null, GithubArtifacts(all.artifacts - entry).snapshotArtifact(sha) { true })
            val replaced = if (entry == all.artifacts.first()) emptyList() else listOf(entry.copy(id = entry.id + 100))
            for (bad in listOf(entry.copy(expired = true), entry.copy(bytes = 0), entry.copy(id = 0)) + replaced) {
                assertEquals(null, GithubArtifacts(all.artifacts - entry + bad).snapshotArtifact(sha) { true })
            }
        }
        assertEquals(null, GithubArtifacts(all.artifacts + all.artifacts.first()).snapshotArtifact(sha) { true })
        assertEquals(null, all.snapshotArtifact("b".repeat(40)) { true })
    }

    @Test fun decodesPublicMetadataWithoutUsingADownloadCredential() {
        val json = """{"workflow_runs":[{"id":42,"head_sha":"$sha","head_branch":"main","event":"push","status":"completed","conclusion":"success","path":".github/workflows/ci.yml","head_repository":{"full_name":"$repo"},"html_url":"https://evil.invalid/ignored"}]}"""
        assertEquals(run(), Json { ignoreUnknownKeys = true }.decodeFromString<GithubWorkflowRuns>(json).runs.single())
    }

    @Test fun skipsIdenticalAndUntrustedRunsWithoutArtifactOrComparisonRequests() = runBlocking {
        for (runs in listOf(emptyList(), listOf(run()), listOf(run().copy(event = "pull_request")))) {
            assertEquals(null, findSnapshotUpdate("aaaaaaa", repo, runs, { true }, { error("Unexpected artifact request") }) { _, _ -> error("Unexpected comparison") })
        }
    }

    @Test fun rejectsOlderDivergentAndUnknownComparisons() = runBlocking {
        for (status in listOf("identical", "behind", "diverged", "unknown")) {
            assertEquals(
                null,
                findSnapshotUpdate("bbbbbbb", repo, listOf(run()), { true }, { artifacts() }) { from, to ->
                    assertEquals("bbbbbbb", from)
                    assertEquals(sha, to)
                    GithubCommitComparison(emptyList(), status)
                },
            )
        }
    }

    @Test fun boundsPublicApiRequestsWhenNoSnapshotIsAvailable() = runBlocking {
        var requests = 0
        val result = findSnapshotUpdate("bbbbbbb", repo, (1L..100L).map { run().copy(id = it) }, { true }, {
            requests++
            GithubArtifacts(emptyList())
        }) { _, _ -> error("No complete snapshot should be compared") }
        assertEquals(null, result)
        assertEquals(10, requests)
    }

    @Test fun skipsIncompleteLatestRunAndOffersTheMatchingArtifactInTheBrowser() = runBlocking {
        val requests = mutableListOf<Long>()
        val result = findSnapshotUpdate("bbbbbbb", repo, listOf(run().copy(id = 43), run()), { it.endsWith("-x86_64.apk") }, { id ->
            requests += id
            if (id == 43L) GithubArtifacts(emptyList()) else artifacts()
        }) { _, _ -> GithubCommitComparison(emptyList(), "ahead") }
        assertEquals(listOf(43L, 42L), requests)
        assertEquals(UpdateDelivery.BROWSER, result?.delivery)
        assertEquals("SNAPSHOT (aaaaaaa)", result?.version)
        assertEquals("https://github.com/$repo/actions/runs/42/artifacts/6", result?.downloadLink)
    }
}
