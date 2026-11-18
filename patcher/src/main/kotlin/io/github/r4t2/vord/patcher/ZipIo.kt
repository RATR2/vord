package io.github.r4t2.vord.patcher

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Streaming zip rewriter. Discord's base APK is ~98 MB with ~9k entries, so entries are copied
 * as raw (still compressed) bytes from one file to another and never held in memory; only
 * entries we replace or add are in memory. Stored entries are re-aligned the way zipalign does
 * (4 bytes, native libraries 16 KB) because dropping the old signature files shifts offsets.
 */
class ZipSource(file: File) : Closeable {
    class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
    )

    private val raf = RandomAccessFile(file, "r")
    val entries: List<Entry>

    init {
        val len = raf.length()
        val tailLen = minOf(len, 65557L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            ) {
                eocd = i
                break
            }
        }
        require(eocd >= 0) { "no end-of-central-directory record in ${file.name}" }
        val e = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val count = e.getShort(eocd + 10).toInt() and 0xFFFF
        val cdSize = e.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = e.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        require(count != 0xFFFF && cdOffset != 0xFFFFFFFFL) { "zip64 archives are not supported" }

        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val list = ArrayList<Entry>(count)
        var p = 0
        repeat(count) {
            require(b.getInt(p) == 0x02014b50) { "bad central directory entry" }
            val flags = b.getShort(p + 8).toInt() and 0xFFFF
            val method = b.getShort(p + 10).toInt() and 0xFFFF
            val time = b.getShort(p + 12).toInt() and 0xFFFF
            val date = b.getShort(p + 14).toInt() and 0xFFFF
            val crc = b.getInt(p + 16).toLong() and 0xFFFFFFFFL
            val csize = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
            val usize = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
            val local = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            list.add(Entry(name, flags, method, time, date, crc, csize, usize, local))
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = list
    }

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }

    /** Reads and inflates an entry fully into memory (only for small files like the manifest). */
    fun readAll(entry: Entry): ByteArray {
        val raw = ByteArray(entry.compressedSize.toInt())
        raf.seek(dataOffset(entry))
        raf.readFully(raw)
        return when (entry.method) {
            0 -> raw
            8 -> {
                val inflater = java.util.zip.Inflater(true)
                inflater.setInput(raw)
                val out = ByteArray(entry.size.toInt())
                var off = 0
                while (off < out.size) {
                    val n = inflater.inflate(out, off, out.size - off)
                    if (n == 0 && (inflater.finished() || inflater.needsInput())) break
                    off += n
                }
                inflater.end()
                out
            }
            else -> error("unsupported compression method ${entry.method}")
        }
    }

    /** Copies the entry's raw (possibly compressed) data bytes to [out]. */
    fun copyRaw(entry: Entry, out: OutputStream) {
        var pos = dataOffset(entry)
        var left = entry.compressedSize
        val buf = ByteArray(256 * 1024)
        while (left > 0) {
            raf.seek(pos)
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            require(n > 0) { "unexpected end of file" }
            out.write(buf, 0, n)
            pos += n
            left -= n
        }
    }

    private fun dataOffset(entry: Entry): Long {
        val h = ByteArray(30)
        raf.seek(entry.localOffset)
        raf.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(0) == 0x04034b50) { "bad local header for ${entry.name}" }
        val nameLen = b.getShort(26).toInt() and 0xFFFF
        val extraLen = b.getShort(28).toInt() and 0xFFFF
        return entry.localOffset + 30 + nameLen + extraLen
    }

    override fun close() = raf.close()
}

class ZipSink(file: File) : Closeable {
    private class Done(
        val name: ByteArray, val flags: Int, val method: Int, val time: Int, val date: Int,
        val crc: Long, val csize: Long, val size: Long, val offset: Long,
    )

    private val out = CountingStream(BufferedOutputStream(file.outputStream(), 1 shl 20))
    private val done = ArrayList<Done>()

    /** Copies [entry] from [src] unchanged (same compression), realigned if stored. */
    fun copy(src: ZipSource, entry: ZipSource.Entry) {
        writeHeader(
            entry.name, entry.flags and 0x0800, entry.method, entry.time, entry.date,
            entry.crc, entry.compressedSize, entry.size,
        )
        src.copyRaw(entry, out)
    }

    /** Adds a new stored entry from memory. */
    fun addStored(name: String, data: ByteArray, time: Int = 0, date: Int = 0x21) {
        val crc = CRC32().also { it.update(data) }.value
        writeHeader(name, 0x0800, 0, time, date, crc, data.size.toLong(), data.size.toLong())
        out.write(data)
    }

    private fun writeHeader(
        name: String, flags: Int, method: Int, time: Int, date: Int,
        crc: Long, csize: Long, size: Long,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val offset = out.count
        var pad = 0
        if (method == 0) {
            val align = if (name.endsWith(".so")) 16384 else 4
            val dataStart = offset + 30 + nameBytes.size
            pad = ((align - dataStart % align) % align).toInt()
            // an extra field needs at least a 4 byte header
            if (pad in 1..3) pad += align
        }
        val h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0x04034b50)
        h.putShort(20)
        h.putShort(flags.toShort())
        h.putShort(method.toShort())
        h.putShort(time.toShort())
        h.putShort(date.toShort())
        h.putInt(crc.toInt())
        h.putInt(csize.toInt())
        h.putInt(size.toInt())
        h.putShort(nameBytes.size.toShort())
        h.putShort(pad.toShort())
        out.write(h.array())
        out.write(nameBytes)
        if (pad > 0) {
            val extra = ByteBuffer.allocate(pad).order(ByteOrder.LITTLE_ENDIAN)
            extra.putShort(0xD935.toShort()) // zipalign's padding field id
            extra.putShort((pad - 4).toShort())
            out.write(extra.array())
        }
        done.add(Done(nameBytes, flags, method, time, date, crc, csize, size, offset))
    }

    override fun close() {
        val cdStart = out.count
        for (d in done) {
            val b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0x02014b50)
            b.putShort(20)
            b.putShort(20)
            b.putShort(d.flags.toShort())
            b.putShort(d.method.toShort())
            b.putShort(d.time.toShort())
            b.putShort(d.date.toShort())
            b.putInt(d.crc.toInt())
            b.putInt(d.csize.toInt())
            b.putInt(d.size.toInt())
            b.putShort(d.name.size.toShort())
            b.putShort(0) // extra
            b.putShort(0) // comment
            b.putShort(0) // disk
            b.putShort(0) // internal attrs
            b.putInt(0) // external attrs
            b.putInt(d.offset.toInt())
            out.write(b.array())
            out.write(d.name)
        }
        val cdSize = out.count - cdStart
        require(done.size < 0xFFFF && out.count < 0xFFFFFFFFL) { "zip64 not supported" }
        val e = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        e.putInt(0x06054b50)
        e.putShort(0)
        e.putShort(0)
        e.putShort(done.size.toShort())
        e.putShort(done.size.toShort())
        e.putInt(cdSize.toInt())
        e.putInt(cdStart.toInt())
        e.putShort(0)
        out.write(e.array())
        out.close()
    }

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun close() = inner.close()
    }
}
