package dev.agentmirror.app.conversation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.agentmirror.app.tsnet.TsnetDial
import dev.agentmirror.app.tsnet.TsnetWire
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// @contract
// @pre authenticated native session identity; execute off main thread
// @post bounded artifact in private cache, shared only through a scoped content URI
// @err finite visible failure; partial file deleted, no credential/URL logging
// @inv same tsnet/LAN route as WS/upload; no redirects, public storage or idle timer
internal fun downloadSessionExport(context: Context, base: String, token: String, ref: String, sessionId: String): Pair<File, String> {
    require(sessionId.isNotBlank() && token.isNotBlank()) { "会话或配对身份未确认" }
    fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    val endpoint = base.trimEnd('/') + "/artifacts/${encoded(sessionId)}/export?ref=${encoded(ref)}"
    val sf = TsnetDial.socketFactoryFor(TsnetWire.state, URI(endpoint).host)
    val builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
    if (sf != null) builder.socketFactory(sf)
    val client = builder.build()
    val directory = File(context.cacheDir, "session-exports").apply { mkdirs() }
    // At most four completed exports (256 MiB); never expose cache root itself.
    directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
        ?.let { files -> files.take((files.size - 3).coerceAtLeast(0)).forEach { it.delete() } }
    var partial: File? = null
    try {
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "导出下载未完成（HTTP ${response.code}）" }
            val body = response.body ?: error("主机未返回导出文件")
            val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            val extension = when (mime) {
                "text/html" -> "html"
                "text/markdown" -> "md"
                else -> error("主机返回了未支持的导出格式")
            }
            val limit = 64L * 1024 * 1024
            check(body.contentLength() <= limit) { "导出文件超过 64 MiB" }
            val file = File.createTempFile("session-", ".$extension", directory)
            partial = file
            var bytes = 0L
            body.byteStream().use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= limit) { "导出文件超过 64 MiB" }
                    output.write(buffer, 0, count)
                }
            } }
            check(bytes > 0 && (body.contentLength() < 0 || bytes == body.contentLength())) { "导出文件未完整下载" }
            partial = null
            return file to mime
        }
    } finally {
        partial?.delete()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

internal fun shareSessionExport(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("会话导出", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享会话导出"))
}
