package com.hippo.ehviewer.updater

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.release.GitHubAssets
import tachiyomi.data.release.GithubCommitComparison
import tachiyomi.data.release.GithubRelease
import tachiyomi.data.release.requirePublicApk

class GithubReleaseTest {
    private val repo = "example/EhViewer"
    private val tag = "v2.34.56-nightly.20261015.1234"
    private fun asset(abi: String) = GitHubAssets("https://github.com/$repo/releases/download/$tag/EhViewer-$abi.apk", "EhViewer-$abi.apk")
    private fun release(assets: List<GitHubAssets> = listOf(asset("universal"), asset("arm64-v8a"))) = GithubRelease(tag, null, "https://github.com/$repo/releases/tag/$tag", assets, true, false, "a".repeat(40))

    @Test fun decodesPublicDownloadUrlWithoutCredentials() {
        val data = """{"tag_name":"$tag","body":null,"html_url":"https://github.com/$repo/releases/tag/$tag","assets":[{"name":"EhViewer-arm64-v8a.apk","browser_download_url":"${asset("arm64-v8a").downloadUrl}","url":"https://api.github.com/repos/$repo/releases/assets/1"}],"prerelease":true,"draft":false,"target_commitish":"${"a".repeat(40)}"}"""
        val result = Json { ignoreUnknownKeys = true }.decodeFromString<GithubRelease>(data)
        assertTrue(result.isNightly)
        assertEquals(asset("arm64-v8a").downloadUrl, result.getDownloadLink(repo) { it.endsWith("-arm64-v8a.apk") })
    }

    @Test fun selectsMatchingApkThenUniversalWithoutUsingAnArbitraryAsset() {
        assertEquals(asset("arm64-v8a").downloadUrl, release().getDownloadLink(repo) { it.endsWith("-arm64-v8a.apk") })
        assertEquals(asset("universal").downloadUrl, release().getDownloadLink(repo) { it.endsWith("-x86_64.apk") })
        for (assets in listOf(emptyList(), listOf(asset("armeabi-v7a")), listOf(GitHubAssets("https://example.invalid", "mapping.txt")))) {
            assertThrows(IllegalArgumentException::class.java) { release(assets).getDownloadLink(repo) { false } }
        }
    }

    @Test fun excludesStableDraftUnrelatedAndUnpinnedPrereleases() {
        val daily = release()
        assertTrue(daily.isNightly)
        for (invalid in listOf(daily.copy(prerelease = false), daily.copy(draft = true), daily.copy(version = "v2.34.56-beta.1"), daily.copy(targetCommitish = "main"))) {
            assertFalse(invalid.isNightly)
        }
    }

    @Test fun rejectsForeignUnsafeOrMismatchedDownloads() {
        val valid = asset("arm64-v8a").downloadUrl
        for (url in listOf(valid.replace("https:", "http:"), valid.replace("github.com", "github.com.evil.test"), valid.replace("github.com/", "github.com@evil.test/"), valid.replace("/$repo/", "/other/repo/"), "$valid?token=secret", "$valid#fragment", "https://api.github.com/repos/$repo/actions/artifacts/1/zip", valid.replace("/releases/", "/x/../releases/"))) {
            assertThrows(IllegalArgumentException::class.java) { requirePublicApk(url, repo) }
        }
        for (bad in listOf(asset("arm64-v8a").copy(name = "different.apk"), asset("arm64-v8a").copy(downloadUrl = valid.replace(tag, "v1.0.0")))) {
            assertThrows(IllegalArgumentException::class.java) { release(listOf(bad)).getDownloadLink(repo) { true } }
        }
    }

    @Test fun dailyChannelSkipsMissingIdenticalOlderAndDivergentBuilds() = runBlocking {
        for (releases in listOf(emptyList(), listOf(release().copy(prerelease = false)), listOf(release()))) {
            val result = findDailyUpdate("aaaaaaa", repo, releases, { true }) { _, _ -> error("Identical or absent daily builds must not request a comparison") }
            assertEquals(null, result)
        }
        for (status in listOf("behind", "identical", "diverged", "unknown")) {
            val result = findDailyUpdate("bbbbbbb", repo, listOf(release()), { true }) { from, to ->
                assertEquals("bbbbbbb", from)
                assertEquals("a".repeat(40), to)
                GithubCommitComparison(emptyList(), status)
            }
            assertEquals(null, result)
        }
        val result = findDailyUpdate("bbbbbbb", repo, listOf(release().copy(prerelease = false), release()), { it.endsWith("-arm64-v8a.apk") }) { _, _ ->
            GithubCommitComparison(emptyList(), "ahead")
        }
        assertEquals(tag.removePrefix("v"), result?.version)
        assertEquals(asset("arm64-v8a").downloadUrl, result?.downloadLink)
        assertEquals("", result?.changelog)
    }
}
