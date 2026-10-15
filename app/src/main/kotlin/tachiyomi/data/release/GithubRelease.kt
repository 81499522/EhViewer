package tachiyomi.data.release

import com.hippo.ehviewer.util.AppConfig
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release from GitHub.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val info: String? = null,
    @SerialName("html_url") val releaseLink: String,
    @SerialName("assets") val assets: List<GitHubAssets>,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("target_commitish") val targetCommitish: String = "",
) {
    val isNightly: Boolean
        get() = !draft && prerelease && Regex("v[0-9]+\\.[0-9]+\\.[0-9]+-nightly\\.[0-9]{8}\\.[1-9][0-9]*").matches(version) &&
            Regex("[0-9a-f]{40}").matches(targetCommitish)

    fun getDownloadLink(repository: String, matches: (String) -> Boolean = AppConfig::matchVariant): String {
        val apks = assets.filter { it.name.endsWith(".apk") && !it.name.contains("marshmallow", true) }
        val asset = apks.find { matches(it.name) } ?: apks.find { it.name.endsWith("-universal.apk") }
        requireNotNull(asset) { "No compatible APK is available" }
        requirePublicApk(asset.downloadUrl, repository)
        val uri = URI(asset.downloadUrl)
        require(uri.path.substringAfterLast('/') == asset.name && uri.path.substringBeforeLast('/').substringAfterLast('/') == version) { "APK asset does not match its release" }
        return asset.downloadUrl
    }
}

fun requirePublicApk(url: String, repository: String) {
    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) { "Invalid repository" }
    val uri = URI(url)
    require(
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null &&
            uri.path.startsWith("/$repository/releases/download/") && uri.path.endsWith(".apk") && uri.normalize() == uri,
    ) { "Expected a public APK from this GitHub repository" }
}

/**
 * Assets class containing download url.
 */
@Serializable
data class GitHubAssets(@SerialName("browser_download_url") val downloadUrl: String, val name: String)
