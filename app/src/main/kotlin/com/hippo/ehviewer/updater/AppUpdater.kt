package com.hippo.ehviewer.updater

import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import okio.Path
import tachiyomi.data.release.GithubCommitComparison
import tachiyomi.data.release.GithubRelease
import tachiyomi.data.release.requirePublicApk

private const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
private const val LATEST_RELEASE_URL = "$API_URL/releases/latest"

object AppUpdater {
    suspend fun checkForUpdate(forceCheck: Boolean = false): Release? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        if (forceCheck || interval != 0 && now > last + interval.days) {
            Settings.lastUpdateTime = now.epochSeconds
            if (Settings.useCIUpdateChannel.value) {
                val releases = ghStatement("$API_URL/releases?per_page=100").executeAndParseAs<List<GithubRelease>>()
                return findDailyUpdate(BuildConfig.COMMIT_SHA, BuildConfig.REPO_NAME, releases, AppConfig::matchVariant) { from, to ->
                    ghStatement("$API_URL/compare/$from...$to").executeAndParseAs<GithubCommitComparison>()
                }
            } else {
                val curVersion = BuildConfig.RAW_VERSION_NAME
                val release = ghStatement(LATEST_RELEASE_URL).executeAndParseAs<GithubRelease>()
                val latestVersion = release.version.removePrefix("v")
                val description = release.info.orEmpty()
                val downloadUrl = release.getDownloadLink(BuildConfig.REPO_NAME)
                if (latestVersion != curVersion) {
                    return Release(latestVersion, description, downloadUrl)
                }
            }
        }
        return null
    }

    suspend fun downloadUpdate(url: String, path: Path) {
        requirePublicApk(url, BuildConfig.REPO_NAME)
        timeoutBySpeed(
            url,
            {
                ghStatement(url) {
                    accept(ContentType.Application.OctetStream)
                    it()
                }
            },
            { _, _, _ -> },
            { response ->
                response.bodyAsChannel().copyTo(path)
            },
        )
    }
}

private suspend inline fun ghStatement(
    url: String,
    builder: HttpRequestBuilder.() -> Unit = {},
) = ktorClient.prepareGet(url) {
    apply(builder)
}

data class Release(
    val version: String,
    val changelog: String,
    val downloadLink: String,
)
