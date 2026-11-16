package cuaderno.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The strokes of one page as bytes: `pages/<id>.strokes` inside a notebook.
 * The layout is written down in docs/format.md; change both together and
 * add a fixture of the old version to the tests.
 */
object StrokeCodec {
    const val VERSION = 1
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte())

    // Which per-sample values follow x and y. A channel that is all zeros is left out.
    private const val PRESSURE = 1
    private const val TIME = 2
    private const val TILT = 4
    private const val ORIENTATION = 8
    private val CHANNELS = intArrayOf(PRESSURE, TIME, TILT, ORIENTATION)

    fun encode(strokes: List<Stroke>): ByteArray {
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        head.put(MAGIC).putShort(VERSION.toShort()).putInt(strokes.size)
        out.write(head.array())
        for (stroke in strokes) {
            var mask = 0
            for ((c, bit) in CHANNELS.withIndex()) {
                if ((0 until stroke.size).any { stroke.channel(it, 2 + c) != 0f }) mask = mask or bit
            }
            val floats = 2 + Integer.bitCount(mask)
            val buffer = ByteBuffer.allocate(14 + stroke.size * floats * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(stroke.tool.code.toByte()).putInt(stroke.color).putFloat(stroke.width)
            buffer.put(mask.toByte()).putInt(stroke.size)
            for (i in 0 until stroke.size) {
                buffer.putFloat(stroke.x(i)).putFloat(stroke.y(i))
                for ((c, bit) in CHANNELS.withIndex()) {
                    if (mask and bit != 0) buffer.putFloat(stroke.channel(i, 2 + c))
                }
            }
            out.write(buffer.array())
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Stroke> {
        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) throw FormatException("not a strokes entry")
            val version = buffer.short.toInt()
            if (version != VERSION) throw FormatException("strokes version $version, this app reads $VERSION")
            val count = buffer.int
            val strokes = ArrayList<Stroke>()
            repeat(count) {
                val tool = Tool.of(buffer.get().toInt())
                val color = buffer.int
                val width = buffer.float
                val mask = buffer.get().toInt()
                if (mask and 0xF0 != 0) throw FormatException("unknown sample channels $mask")
                val n = buffer.int
                if (n <= 0 || n.toLong() * 8 > buffer.remaining()) throw FormatException("truncated stroke")
                val data = FloatArray(n * Stroke.STRIDE)
                for (i in 0 until n) {
                    data[i * Stroke.STRIDE] = buffer.float
                    data[i * Stroke.STRIDE + 1] = buffer.float
                    for ((c, bit) in CHANNELS.withIndex()) {
                        if (mask and bit != 0) data[i * Stroke.STRIDE + 2 + c] = buffer.float
                    }
                }
                strokes.add(Stroke(tool, color, width, data))
            }
            if (buffer.hasRemaining()) throw FormatException("${buffer.remaining()} bytes after the last stroke")
            return strokes
        } catch (e: java.nio.BufferUnderflowException) {
            throw FormatException("truncated strokes entry")
        }
    }
}
