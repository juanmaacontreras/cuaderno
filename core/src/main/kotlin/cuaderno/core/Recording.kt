package cuaderno.core

import java.util.Locale

/**
 * What the pen sent while someone wrote, sample by sample, so the same
 * handwriting can be replayed in tests: reference images and timings that do
 * not need the tablet. A text file, one sample per line:
 *
 *     # cuaderno pen recording v1
 *     # device=SM-X520 width=2304 height=1440 density=2.0
 *     D,0,412.5,300.25,0.31,0.2,1.1,2,0
 *
 * Columns: action (D down, M move, U up, C cancel), milliseconds since the
 * recording began, x and y in pixels, pressure, tilt and orientation in
 * radians, Android tool type (1 finger, 2 stylus, 4 eraser), button state.
 */
class Recording(
    val device: String,
    val width: Int,
    val height: Int,
    val density: Float,
    val samples: List<Sample>,
) {
    class Sample(
        val action: Char,
        val timeMs: Long,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val tilt: Float,
        val orientation: Float,
        val tool: Int,
        val buttons: Int,
    )

    fun format(): String = buildString {
        append(HEADER).append('\n')
        append("# device=${device.replace(' ', '_')} width=$width height=$height density=$density\n")
        for (s in samples) {
            append(String.format(Locale.ROOT, "%c,%d,%.2f,%.2f,%.4f,%.4f,%.4f,%d,%d\n",
                s.action, s.timeMs, s.x, s.y, s.pressure, s.tilt, s.orientation, s.tool, s.buttons))
        }
    }

    companion object {
        private const val HEADER = "# cuaderno pen recording v1"

        fun parse(text: String): Recording {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != HEADER) throw FormatException("not a pen recording")
            val meta = lines.getOrNull(1).orEmpty().removePrefix("#").trim().split(' ')
                .mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 } }
                .associate { it[0] to it[1] }
            val samples = lines.drop(2).filter { it.isNotBlank() }.map { line ->
                val f = line.split(',')
                if (f.size != 9 || f[0].length != 1 || f[0][0] !in "DMUC") throw FormatException("bad sample: $line")
                try {
                    Sample(f[0][0], f[1].toLong(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(),
                        f[5].toFloat(), f[6].toFloat(), f[7].toInt(), f[8].toInt())
                } catch (e: NumberFormatException) {
                    throw FormatException("bad sample: $line")
                }
            }
            return Recording(
                device = meta["device"] ?: "unknown",
                width = meta["width"]?.toIntOrNull() ?: 0,
                height = meta["height"]?.toIntOrNull() ?: 0,
                density = meta["density"]?.toFloatOrNull() ?: 1f,
                samples = samples,
            )
        }
    }
}
