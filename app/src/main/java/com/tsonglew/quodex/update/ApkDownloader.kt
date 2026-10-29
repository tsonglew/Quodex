package com.tsonglew.quodex.update

import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Uses its own public HTTP client; account credentials never enter the download path. */
class ApkDownloader(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()

    suspend fun download(update: AppUpdate, destination: File, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        var url = trustedApkUrl(update) ?: throw IOException("更新缺少有效的安装包信息，请重新检查更新。")
        val checksum = update.apkSha256?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            ?: throw IOException("此版本缺少安装包校验信息，请稍后重新检查更新。")
        if (update.apkSize !in 1..GitHubUpdateClient.MAX_APK_BYTES) throw IOException("安装包大小不受支持。")
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        var complete = false
        try {
            for (redirect in 0..5) {
                val call = client.newCall(Request.Builder().url(url)
                    .header("User-Agent", "Quodex-InApp-Update").header("Accept-Encoding", "identity").build())
                val finished = coroutineScope {
                    // Cancelling the UI operation also closes a blocked socket/read immediately.
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
                            if (response.code in listOf(301, 302, 303, 307, 308)) {
                                val next = response.header("Location")?.let(url::resolve)
                                if (redirect == 5 || next == null || !trustedAssetRedirect(next))
                                    throw IOException("下载地址无效，请重新检查更新。")
                                url = next
                                false
                            } else {
                                if (response.code != 200) throw IOException("下载失败（HTTP ${response.code}），请稍后重试。")
                                val body = response.body ?: throw IOException("安装包内容为空。")
                                if (body.contentLength() != -1L && body.contentLength() != update.apkSize)
                                    throw IOException("安装包大小不匹配，请重新下载。")
                                val digest = MessageDigest.getInstance("SHA-256")
                                var received = 0L
                                var lastPercent = -1L
                                body.byteStream().use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count == -1) break
                                            received += count
                                            if (received > update.apkSize) throw IOException("安装包大小不匹配，请重新下载。")
                                            digest.update(buffer, 0, count)
                                            output.write(buffer, 0, count)
                                            val percent = received * 100 / update.apkSize
                                            if (percent != lastPercent) { progress(received, update.apkSize); lastPercent = percent }
                                        }
                                    }
                                }
                                ensureActive()
                                if (received != update.apkSize || digest.digest().hex() != checksum)
                                    throw IOException("安装包校验失败，请重新下载。")
                                if (!partial.renameTo(destination)) throw IOException("无法保存安装包，请检查存储空间。")
                                true
                            }
                        }
                    } catch (failure: IOException) {
                        // Socket cancellation reports IOException; preserve coroutine cancellation to callers.
                        ensureActive()
                        throw failure
                    } finally { cancellation.cancel() }
                }
                if (finished) { complete = true; break }
            }
        } finally {
            partial.delete()
            if (!complete) destination.delete()
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun trustedApkUrl(update: AppUpdate): HttpUrl? {
    val release = update.releaseUrl.toHttpUrlOrNull() ?: return null
    val tag = release.pathSegments.lastOrNull()?.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(it) } ?: return null
    if (update.releaseUrl != "https://github.com/tsonglew/Quodex/releases/tag/$tag") return null
    if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?").matches(update.versionName)) return null
    val expected = "https://github.com/tsonglew/Quodex/releases/download/$tag/"
    val url = update.apkUrl ?: return null
    if (url !in listOf("${expected}Quodex-${update.versionName}.apk", "${expected}QuotaPal-${update.versionName}.apk")) return null
    return url.toHttpUrlOrNull()
}

internal fun trustedAssetRedirect(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
    url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
