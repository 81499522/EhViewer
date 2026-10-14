@file:JvmName("ArchiveKt")

package com.hippo.ehviewer.jni

import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.management.ManagementFactory
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

private fun touchPage(buffer: ByteBuffer) {
    var index = 0
    while (index < buffer.capacity()) {
        checksum += buffer.get(index)
        index += 4096
    }
}

// Keep optional statistics out of the timed method and the hot page-touch loop.
private fun measurePage(index: Int, mutation: Int): Long {
    val before = System.nanoTime()
    val buffer = checkNotNull(extractToByteBuffer(index))
    try {
        touchPage(buffer)
        if (mutation >= 0 && buffer.capacity() > 0) {
            val changed = ((mutation * 104729L) % buffer.capacity()).toInt()
            buffer.put(changed, (buffer.get(changed).toInt() xor 127).toByte())
        }
    } finally {
        releaseByteBuffer(buffer)
    }
    return System.nanoTime() - before
}

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
    val cpuSamples = options.has("--cpu-samples")
    val samples = options.has("--samples") || cpuSamples
    val mutate = options.has("--mutate")
    val collectors = if (samples) ManagementFactory.getGarbageCollectorMXBeans() else emptyList()
    val thread = if (cpuSamples) {
        ManagementFactory.getThreadMXBean().also {
            require(it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled) { "Thread CPU timing is unavailable" }
        }
    } else {
        null
    }
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
            val cpuTimes = if (cpuSamples) LongArray(loops) else LongArray(0)
            val starts = if (cpuSamples) LongArray(loops) else LongArray(0)
            var firstNano = 0L
            var uptime = 0L
            var gcCount = 0L
            var gcTime = 0L
            repeat(warmup + loops) { iteration ->
                if (iteration == warmup && samples) {
                    gcCount = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
                    gcTime = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
                    if (cpuSamples) uptime = ManagementFactory.getRuntimeMXBean().uptime
                }
                val cpuBefore = thread?.currentThreadCpuTime ?: 0L
                val before = if (cpuSamples) System.nanoTime() else 0L
                val duration = measurePage((iteration * 17) % pages, if (mutate) iteration else -1)
                if (iteration >= warmup) times[iteration - warmup] = duration
                if (iteration >= warmup && thread != null) {
                    if (iteration == warmup) firstNano = before
                    cpuTimes[iteration - warmup] = thread.currentThreadCpuTime - cpuBefore
                    starts[iteration - warmup] = before - firstNano
                }
            }
            // Snapshot counters before boxing/formatting sample data can itself trigger GC.
            val gcAfter = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
            val gcTimeAfter = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
            val measured = if (samples) {
                mapOf(
                    "samples_ns" to times.toList(),
                    "gc_count" to (gcAfter - gcCount),
                    "gc_ms" to (gcTimeAfter - gcTime),
                    "gc_supported" to collectors.all { it.collectionCount >= 0 && it.collectionTime >= 0 },
                    "gc_collectors" to collectors.map { it.name },
                )
            } else {
                emptyMap()
            }
            val cpu = if (cpuSamples) {
                mapOf(
                    "thread_cpu_ns" to cpuTimes.toList(),
                    "starts_ns" to starts.toList(),
                    "measurement_uptime_ms" to uptime,
                )
            } else {
                emptyMap()
            }
            times.sort()
            return mapOf(
                "archive" to archive.fileName.toString(), "library" to library.toString(),
                "loops" to loops, "warmup" to warmup, "retained" to retained,
                "mutate" to mutate,
                "open_ms" to opened / 1e6, "median_ms" to times[loops / 2] / 1e6,
                "p95_ms" to times[(loops * 95 / 100).coerceAtMost(loops - 1)] / 1e6,
                "checksum" to checksum,
            ) + measured + cpu
        } finally {
            held.forEach(::releaseByteBuffer)
            closeArchive()
        }
    }
}
