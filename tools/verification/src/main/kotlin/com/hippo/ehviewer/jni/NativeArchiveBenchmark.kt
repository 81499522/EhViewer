@file:JvmName("ArchiveKt")

package com.hippo.ehviewer.jni

import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import verification.Options

// Match the existing application JNI class without compiling Android application code.
private external fun openArchive(fd: Int, size: Long, sort: Boolean): Int
private external fun closeArchive()
private external fun extractToByteBuffer(index: Int): ByteBuffer?
private external fun releaseByteBuffer(buffer: ByteBuffer)

@Volatile private var checksum = 0L

fun benchmarkArchives(output: Path): Map<String, Any> {
    Files.createDirectories(output)
    val result = mutableListOf<String>()
    for (stored in listOf(true, false)) {
        val path = output.resolve(if (stored) "large-stored.zip" else "large-deflated.zip")
        ZipOutputStream(path.outputStream()).use { zip ->
            for (page in listOf(10, 2, 1)) {
                val bytes = ByteArray(4 shl 20) { page.toByte() }
                val entry = ZipEntry("page$page.jpg").apply {
                    time = 0
                    if (stored) {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        result += path.toString()
    }
    return mapOf("archives" to result, "pages" to 3, "page_bytes" to (4 shl 20))
}

fun nativeArchiveBenchmark(options: Options): Map<String, Any> {
    val library = options.path("--library")
    val archive = options.path("--archive")
    val loops = options.get("--loops", "300").toInt()
    val warmup = options.get("--warmup", "0").toInt()
    val retained = options.get("--retained", "0").toInt()
    require(loops in 1..100_000 && warmup in 0..100_000 && retained in 0..64)
    System.load(library.toString())
    checksum = 0
    FileInputStream(archive.toFile()).use { input ->
        val field = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
        val start = System.nanoTime()
        val pages = openArchive(field.getInt(input.fd), Files.size(archive), true)
        require(pages > 0) { "Unable to open benchmark archive" }
        val opened = System.nanoTime() - start
        val held = mutableListOf<ByteBuffer>()
        try {
            repeat(retained) { held += checkNotNull(extractToByteBuffer(it % pages)) }
            val times = LongArray(loops)
            repeat(warmup + loops) { iteration ->
                val before = System.nanoTime()
                val buffer = checkNotNull(extractToByteBuffer((iteration * 17) % pages))
                try {
                    // Same 4-KiB page-touch workload as the original C/Rust JNI baseline.
                    var index = 0
                    while (index < buffer.capacity()) {
                        checksum += buffer.get(index)
                        index += 4096
                    }
                } finally {
                    releaseByteBuffer(buffer)
                }
                if (iteration >= warmup) times[iteration - warmup] = System.nanoTime() - before
            }
            times.sort()
            return mapOf(
                "archive" to archive.fileName.toString(), "library" to library.toString(),
                "loops" to loops, "warmup" to warmup, "retained" to retained,
                "open_ms" to opened / 1e6, "median_ms" to times[loops / 2] / 1e6,
                "p95_ms" to times[(loops * 95 / 100).coerceAtMost(loops - 1)] / 1e6,
                "checksum" to checksum,
            )
        } finally {
            held.forEach(::releaseByteBuffer)
            closeArchive()
        }
    }
}
