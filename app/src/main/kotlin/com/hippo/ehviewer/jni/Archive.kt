package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int
external fun extractToByteBuffer(index: Int): ByteBuffer?

/** Read-only output, except independently writable GIF data when the platform workaround needs it. */
internal external fun extractForReading(index: Int, rewriteGif: Boolean): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String
external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveFdBatch(fdBatch: IntArray, names: Array<String>, arcFd: Int, size: Int)
