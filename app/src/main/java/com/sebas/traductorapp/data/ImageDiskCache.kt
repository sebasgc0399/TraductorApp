package com.sebas.traductorapp.data

import java.io.File
import java.security.MessageDigest

/** Caché en disco de las imágenes de página, por URL. Se recorta por antigüedad al superar [maxBytes]. */
class ImageDiskCache(private val dir: File, private val maxBytes: Long) {

    init {
        dir.mkdirs()
    }

    private fun fileFor(url: String): File = File(dir, sha256(url) + ".img")

    fun get(url: String): File? = fileFor(url).takeIf { it.isFile && it.length() > 0 }?.also {
        it.setLastModified(System.currentTimeMillis())
    }

    fun put(url: String, bytes: ByteArray): File {
        val target = fileFor(url)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        return target
    }

    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        fun sniffMime(head: ByteArray): String? {
            fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
                at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "image/gif"
                at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code &&
                    at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
                at(4) == 'f'.code && at(5) == 't'.code && at(6) == 'y'.code && at(7) == 'p'.code &&
                    at(8) == 'a'.code && at(9) == 'v'.code -> "image/avif"
                else -> null
            }
        }

        fun sniffMime(file: File): String? = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(16)
                val n = input.read(head)
                if (n <= 0) null else sniffMime(head.copyOf(n))
            }
        }.getOrNull()

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
