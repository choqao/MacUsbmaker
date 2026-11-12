package com.example.macusb

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class MacOsTarget(
    val name: String,
    val boardId: String,
    val mlb: String = "00000000000000000",
    val os: String = "latest"
)

/**
 * Board IDs follow OpenCorePkg/Utilities/macrecovery/boards.json.
 * Verify against the current file; Apple changes what each board resolves to.
 */
val MAC_OS_TARGETS = listOf(
    MacOsTarget("Big Sur (11)", "Mac-E43C1C25D4880AD6"),
    MacOsTarget("Monterey (12)", "Mac-FFE5EF870D7BA81A"),
    MacOsTarget("Ventura (13)", "Mac-4B682C642B45593E"),
    MacOsTarget("Sonoma / latest", "Mac-827FAC58A8FDFA22"),
)

data class RecoveryAsset(
    val dmgUrl: String, val dmgToken: String,
    val chunklistUrl: String, val chunklistToken: String
)

class AppleRecoveryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val rnd = SecureRandom()

    private fun randomHex(bytes: Int): String {
        val a = ByteArray(bytes).also { rnd.nextBytes(it) }
        return a.joinToString("") { "%02X".format(it) }
    }

    fun fetchAsset(target: MacOsTarget): RecoveryAsset {
        val session = client.newCall(
            Request.Builder().url("http://osrecovery.apple.com/")
                .header("User-Agent", "InternetRecovery/1.0").get().build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Handshake failed: HTTP ${r.code}")
            r.headers("Set-Cookie").firstOrNull { it.startsWith("session=") }
                ?.substringBefore(";") ?: throw IOException("No session cookie from Apple")
        }

        val payload = listOf(
            "cid=${randomHex(8)}", "sn=${target.mlb}", "bid=${target.boardId}",
            "k=${randomHex(32)}", "fg=${randomHex(32)}", "os=${target.os}"
        ).joinToString("\n")

        val map = client.newCall(
            Request.Builder().url("http://osrecovery.apple.com/InstallationPayload/RecoveryImage")
                .header("User-Agent", "InternetRecovery/1.0")
                .header("Cookie", session)
                .post(payload.toRequestBody("text/plain".toMediaType())).build()
        ).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Apple refused request: HTTP ${r.code} (check board ID)")
            body.lines().mapNotNull { l ->
                val i = l.indexOf(": ")
                if (i > 0) l.substring(0, i).trim() to l.substring(i + 2).trim() else null
            }.toMap()
        }

        fun need(k: String) = map[k] ?: throw IOException("Apple response missing '$k'")
        return RecoveryAsset(need("AU"), need("AH"), need("CU"), need("CH"))
    }

    fun download(url: String, token: String, sink: OutputStream, onProgress: (Long, Long) -> Unit) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "InternetRecovery/1.0")
            .header("Cookie", "AssetToken=$token").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty body")
            val total = body.contentLength()
            val buf = ByteArray(256 * 1024)
            var done = 0L
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            sink.flush()
            if (total > 0 && done != total) throw IOException("Truncated download ($done/$total)")
        }
    }
}
