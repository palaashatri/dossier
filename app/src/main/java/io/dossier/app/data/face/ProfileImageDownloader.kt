package io.dossier.app.data.face

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads public profile avatar images to the app cache for local face scoring.
 * Image bytes stay on-device; downloads are size-bounded and never persisted as
 * long-term identity data beyond the scan cache directory.
 */
class ProfileImageDownloader(context: Context) {
    private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun download(imageUrl: String): Uri? {
        val normalized = normalizeHttpUrl(imageUrl) ?: return null
        val target = File(cacheDir, cacheFileName(normalized))
        if (target.exists() && target.length() in MIN_BYTES..MAX_BYTES) {
            return Uri.fromFile(target)
        }

        return runCatching {
            val request = Request.Builder()
                .url(normalized)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val contentType = body.contentType()?.toString().orEmpty().lowercase()
                if (contentType.isNotBlank() && !contentType.startsWith("image/") &&
                    !contentType.contains("octet-stream")
                ) {
                    return@use null
                }

                val temp = File(cacheDir, "${target.name}.tmp")
                try {
                    FileOutputStream(temp).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_BYTES) {
                                    error("Profile image exceeds size limit.")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (temp.length() < MIN_BYTES) {
                        error("Profile image too small.")
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        error("Unable to store profile image cache file.")
                    }
                    Uri.fromFile(target)
                } catch (e: Exception) {
                    if (temp.exists()) temp.delete()
                    throw e
                }
            }
        }.getOrNull()
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val CACHE_DIR_NAME = "profile-image-cache"
        private const val MIN_BYTES = 256L
        private const val MAX_BYTES = 5L * 1024L * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

        fun normalizeHttpUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            val withScheme = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> return null
            }
            return withScheme.substringBefore('#').takeIf { it.isNotBlank() }
        }

        private fun cacheFileName(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val extension = when {
                url.contains(".png", ignoreCase = true) -> "png"
                url.contains(".webp", ignoreCase = true) -> "webp"
                url.contains(".gif", ignoreCase = true) -> "gif"
                else -> "jpg"
            }
            return "$digest.$extension"
        }
    }
}
