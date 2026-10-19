package com.hippo.ehviewer.jni

import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import verification.Options

private inline fun <T> archive(path: Path, block: (Int) -> T): T = FileInputStream(path.toFile()).use { input ->
    val field = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
    val count = openArchive(field.getInt(input.fd), Files.size(path), true)
    require(count > 0) { "Cannot open $path" }
    try {
        if (needPassword()) check(providePassword("baseline"))
        block(count)
    } finally {
        closeArchive()
    }
}

private fun digest(buffer: ByteBuffer): String = MessageDigest.getInstance("SHA-256").run {
    update(buffer.duplicate())
    digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** One unchanged client for the original C ABI and either Rust build; no lifetime-unsafe C probes. */
fun nativeArchiveDifferential(options: Options): Map<String, Any> {
    System.load(options.path("--library").toString())
    val directory = options.path("--fixtures")
    val readonly = options.has("--readonly")
    fun extract(index: Int): ByteBuffer = requireNotNull(if (readonly) extractForReading(index, false) else extractToByteBuffer(index)).also {
        check(!readonly || it.isReadOnly)
    }
    val names = listOf("stored.zip", "deflated.zip", "pages.tar", "pages.tgz", "pages.txz", "pages.7z", "password.zip", "zipcrypto.zip", "stored.rar", "compressed.rar", "solid.rar", "multiple_files.rar", "large-deflated.zip")
    val results = names.associateWith { name ->
        archive(directory.resolve(name)) { count ->
            (0 until count).map { index ->
                val first = extract(index)
                val expected = try {
                    mapOf("extension" to getExtension(index), "size" to first.capacity(), "sha256" to digest(first))
                } finally {
                    releaseByteBuffer(first)
                }
                expected
            }.also { expected ->
                for (index in (count - 1 downTo 0).toList() + listOf(0, count - 1, 0)) {
                    val buffer = extract(index)
                    try {
                        check(digest(buffer) == expected[index]["sha256"])
                    } finally {
                        releaseByteBuffer(buffer)
                    }
                }
            }
        }
    }
    return mapOf("archives" to results)
}

/** Exercises actual JNI objects, rather than substituting a Rust-only registry test. */
fun nativeArchiveRegression(options: Options): Map<String, Any> {
    System.load(options.path("--library").toString())
    val directory = options.path("--fixtures")
    var checks = 0
    // This is the first readonly JNI call in a fresh process: race initialization of the cached
    // method ID and its declaring-class GlobalRef, then use the cache from all attached threads.
    archive(directory.resolve("stored.zip")) {
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        try {
            val workers = (0 until 8).map {
                executor.submit {
                    ready.countDown()
                    go.await()
                    val buffer = requireNotNull(extractForReading(0, false))
                    try {
                        check(buffer.isReadOnly && buffer.get(0) == 'o'.code.toByte())
                    } finally {
                        releaseByteBuffer(buffer)
                    }
                }
            }
            ready.await()
            go.countDown()
            workers.forEach { it.get() }
            checks += 8
        } finally {
            executor.shutdownNow()
        }
    }
    archive(directory.resolve("stored.zip")) {
        repeat(32) {
            val first = requireNotNull(extractForReading(0, false))
            try {
                first.limit(1).position(1)
                val second = requireNotNull(extractForReading(0, false))
                val other = requireNotNull(extractForReading(1, false))
                try {
                    check(first !== second && second.isReadOnly)
                    check(second.position() == 0 && second.limit() == second.capacity())
                    check(second.get(0) == 'o'.code.toByte() && other.get(0) == 't'.code.toByte())
                    checks += 3
                } finally {
                    releaseByteBuffer(second)
                    releaseByteBuffer(other)
                }
            } finally {
                releaseByteBuffer(first)
            }
        }
    }
    for (name in listOf("stored.zip", "deflated.zip", "password.zip", "pages.7z", "stored.rar")) {
        archive(directory.resolve(name)) { count ->
            val held = requireNotNull(extractForReading(0, false))
            try {
                check(held.isDirect && held.isReadOnly)
                val expected = digest(held)
                for (view in listOf(held, held.slice(), held.duplicate())) {
                    check(view.isReadOnly)
                    check(runCatching { view.put(0, 99) }.exceptionOrNull() is ReadOnlyBufferException)
                    check(runCatching { rewriteGifSource(view) }.isFailure)
                    checks += 3
                }
                releaseByteBuffer(held.duplicate())
                val executor = Executors.newFixedThreadPool(8)
                try {
                    val workers = (0 until 8).map {
                        executor.submit {
                            repeat(100) {
                                val readonly = requireNotNull(extractForReading(0, false))
                                val writable = requireNotNull(extractToByteBuffer(0))
                                try {
                                    writable.put(0, 99)
                                    check(digest(readonly) == expected)
                                } finally {
                                    releaseByteBuffer(writable)
                                    releaseByteBuffer(readonly)
                                    releaseByteBuffer(readonly)
                                }
                            }
                        }
                    }
                    workers.forEach { it.get() }
                } finally {
                    executor.shutdownNow()
                }
                checks += 800
                check(extractForReading(-1, false) == null)
                check(extractForReading(count, false) == null)
                closeArchive()
                check(digest(held) == expected)
                checks += 3
            } finally {
                releaseByteBuffer(held)
                releaseByteBuffer(held)
            }
        }
    }
    archive(directory.resolve("stored.zip")) {
        val held = ArrayList<ByteBuffer>()
        try {
            repeat(128) { held += requireNotNull(extractForReading(0, false)) }
            check(extractForReading(0, false) == null)
            val old = held.removeAt(0)
            releaseByteBuffer(old)
            val replacement = requireNotNull(extractForReading(0, false))
            held += replacement
            releaseByteBuffer(old)
            check(extractForReading(0, false) == null)
            closeArchive()
            check(replacement.get(0) == 'o'.code.toByte())
            checks += 3
        } finally {
            held.forEach(::releaseByteBuffer)
        }
    }
    if (options.has("--large-archive")) {
        archive(options.path("--large-archive")) {
            val held = ArrayList<ByteBuffer>()
            try {
                repeat(32) {
                    held += requireNotNull(extractForReading(0, false)).also { check(it.capacity() == (16 shl 20)) }
                }
                // 32 registrations, below the count limit: this failure must be the byte budget.
                check(extractForReading(0, false) == null)
                val old = held.removeAt(0)
                releaseByteBuffer(old)
                held += requireNotNull(extractForReading(0, false))
                releaseByteBuffer(old)
                check(extractForReading(0, false) == null)
                closeArchive()
                held.forEach { check(it.get(0) == 1.toByte()) }
                checks += 3
            } finally {
                held.forEach(::releaseByteBuffer)
            }
        }
    }
    return mapOf("checks" to checks, "result" to "passed")
}
