package com.flue.launcher.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 读写超过上限时抛出。按**实际**流过的字节计数，不信任 ZIP 条目或 provider 自报的大小
 * （R37：限制实际解包字节）。
 */
class SizeLimitExceededException(
    val what: String,
    val limitBytes: Long,
) : IOException("$what 超过大小上限（${limitBytes / (1024L * 1024L)}MB）")

/**
 * 把 [this] 拷到 [output]，累计超过 [limitBytes] 立即抛 [SizeLimitExceededException]。
 * 只用 `read(ByteArray)`，不依赖 API 33 的 `transferTo` / `readNBytes`。
 */
fun InputStream.copyToBounded(output: OutputStream, limitBytes: Long, what: String): Long {
    require(limitBytes >= 0L)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
        if (total > limitBytes) throw SizeLimitExceededException(what, limitBytes)
        output.write(buffer, 0, read)
    }
}

/** 有上限的 `readBytes()`。 */
fun InputStream.readBytesBounded(limitBytes: Long, what: String): ByteArray {
    val output = ByteArrayOutputStream()
    copyToBounded(output, limitBytes, what)
    return output.toByteArray()
}

/** 关闭时只 flush、不关闭下层流，用于把内层 ZIP 直接流式写进外层 ZIP 的一个条目。 */
class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() = delegate.flush()
}
