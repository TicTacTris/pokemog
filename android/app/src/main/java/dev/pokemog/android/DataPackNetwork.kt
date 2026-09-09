package dev.pokemog.android

import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException

internal const val DATA_API = "https://api.github.com/repos/TicTacTris/pokemog-data/releases/latest"
private const val RELEASE_PREFIX = "/TicTacTris/pokemog-data/releases/download/"

internal fun releaseAssetUrl(value: String, name: String, tag: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
        uri.fragment == null && uri.rawQuery == null && uri.rawPath == "$RELEASE_PREFIX$tag/$name"
}.getOrDefault(false)

internal fun trustedRedirect(value: String, original: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host ?: return false
    uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
        (value == original || host == "release-assets.githubusercontent.com" || host == "objects.githubusercontent.com" ||
            host.matches(Regex("github-production-release-asset-[0-9a-f-]+\\.s3\\.amazonaws\\.com")))
}.getOrDefault(false)

internal object DataPackNetwork {
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "data-request-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun get(url: String, limit: Int, updateDeadline: Long = Long.MAX_VALUE): ByteArray {
        val deadline = minOf(updateDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
        fun remaining(): Long = (deadline - System.nanoTime()).also {
            if (it <= 0) throw SocketTimeoutException("Data request deadline exceeded")
        }
        require(limit in 1..PACK_LIMIT)
        if (url != DATA_API) {
            val parts = URI(url).rawPath.split('/')
            require(parts.size == 7 && parts[5].matches(Regex("data-[1-9][0-9]{0,15}")))
            require(parts[6] in PACK_FILES + "manifest.json" && releaseAssetUrl(url, parts[6], parts[5]))
        }
        var current = url
        repeat(6) {
            remaining()
            val connection = URL(current).openConnection() as HttpsURLConnection
            var timeout: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                timeout = deadlines.schedule({ connection.disconnect() }, remaining(), TimeUnit.NANOSECONDS)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", if (url == DATA_API) "application/vnd.github+json" else "application/octet-stream")
                connection.setRequestProperty("User-Agent", "PokeMog-Android-data-updater")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    // The API has one exact endpoint and no redirects. Asset CDN redirects only.
                    require(url != DATA_API)
                    val next = URI(current).resolve(connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                    require(trustedRedirect(next, url)) { "Untrusted data redirect" }
                    current = next
                } else {
                    require(code == 200) { "Data HTTP $code" }
                    require(connection.contentEncoding == null || connection.contentEncoding == "identity")
                    return connection.inputStream.use { it.boundedBytes(limit) { remaining() } }.also { remaining() }
                }
            } finally { timeout?.cancel(false); connection.disconnect() }
        }
        error("Too many data redirects")
    }

    fun releaseAssets(bytes: ByteArray): Map<String, String> {
        val release = strictJson(utf8(bytes)) as JSONObject
        require(release.get("draft") == false && release.get("prerelease") == false)
        val tag = strictString(release.get("tag_name"))
        require(tag.matches(Regex("data-[1-9][0-9]{0,15}")))
        val assets = release.getJSONArray("assets")
        require(assets.length() == 5)
        val result = mutableMapOf<String, String>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = strictString(asset.get("name"))
            require(name in PACK_FILES || name == "manifest.json")
            val url = strictString(asset.get("browser_download_url"))
            require(releaseAssetUrl(url, name, tag))
            require(result.put(name, url) == null)
        }
        require(result.keys == PACK_FILES + "manifest.json")
        return result
    }
}
