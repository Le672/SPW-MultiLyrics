package com.spw.multilyrics.provider

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.zip.GZIPInputStream

/** 最小化的 HTTP 抽象（基于 HttpURLConnection，因为 SPW 运行时不包含 java.net.http）。 */
interface ProviderHttp {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String
    fun postForm(url: String, values: Map<String, String>, headers: Map<String, String> = emptyMap()): String
}

class ProviderHttpClient(
    connectTimeout: Duration = Duration.ofSeconds(3),
    private val requestTimeout: Duration = Duration.ofSeconds(5),
) : ProviderHttp {
    private val connectTimeoutMs = connectTimeout.toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private val readTimeoutMs = requestTimeout.toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    override fun get(url: String, headers: Map<String, String>): String = send(url, "GET", null, null, headers)

    override fun postJson(url: String, body: String, headers: Map<String, String>): String = send(
        url, "POST", "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8), headers,
    )

    override fun postForm(url: String, values: Map<String, String>, headers: Map<String, String>): String {
        val body = values.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return send(url, "POST", "application/x-www-form-urlencoded; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8), headers)
    }

    private fun send(
        url: String, method: String, contentType: String?, body: ByteArray?, headers: Map<String, String>,
    ): String {
        // 优先使用环境变量代理（与 curl 行为对齐）：HTTPS_PROXY / HTTP_PROXY
        // 无环境变量时 proxy == null，走默认直连，零影响
        val connection = (proxy?.let { URI.create(url).toURL().openConnection(it) }
            ?: URI.create(url).toURL().openConnection()) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            check(status in 200..299) { "HTTP $status" }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            return stream.bufferedReader(responseCharset(connection.contentType)).use { it.readText() }
        } finally {
            connection.errorStream?.close()
            connection.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 MultiLyrics/0.1.0"

        /** 解析环境变量代理（HTTPS_PROXY 优先，回退 HTTP_PROXY；支持大小写变体）。无则返回 null。 */
        private val proxy: Proxy? = runCatching {
            val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
                ?: System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy")
            if (raw.isNullOrBlank()) null
            else {
                val u = URI(raw)
                Proxy(Proxy.Type.HTTP, InetSocketAddress(u.host, if (u.port > 0) u.port else 80))
            }
        }.getOrNull()

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun responseCharset(contentType: String?) = contentType
            ?.let { Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { runCatching { java.nio.charset.Charset.forName(it.trim('"')) }.getOrNull() }
            ?: StandardCharsets.UTF_8
    }
}
