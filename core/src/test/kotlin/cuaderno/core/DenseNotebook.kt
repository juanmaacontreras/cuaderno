package cuaderno.core

import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * Not a check: writes a densely written notebook to measure how the page moves
 * with a lot on it, always with the same strokes. Five sheets of letter-sized
 * squiggles on every grid line. Does nothing unless asked:
 *
 *   CUADERNO_DENSE=/path/out.cuaderno CUADERNO_STROKES=1500 gradle :core:test --tests '*DenseNotebook*' --rerun
 *
 * then put it on the emulator as the app's notebook (files/notebooks/cuaderno.cuaderno,
 * through `run-as`) and read "Medir fluidez" while scrolling.
 */
class DenseNotebook {
    @Test fun write() {
        val out = System.getenv("CUADERNO_DENSE") ?: return
        val perPage = System.getenv("CUADERNO_STROKES")?.toInt() ?: 1500
        val random = java.util.Random(1)
        val pages = (0 until 5).map { n ->
            val strokes = ArrayList<Stroke>()
            var x = 40f
            var y = 50f
            while (strokes.size < perPage) {
                val b = StrokeBuilder(Tool.PEN, INK, 1.2f)
                val w = 5f + random.nextFloat() * 6f
                val samples = 18 + random.nextInt(20)
                for (k in 0 until samples) {
                    val t = k / (samples - 1f)
                    b.add(x + t * w + 2f * sin(t * 9f + random.nextFloat()), y - 4f * abs(sin(t * 6f)) + random.nextFloat(), 0.5f, k * 4f)
                }
                strokes.add(b.build())
                x += w + 2f
                if (x > 560f) { x = 40f; y += 14.17f; if (y > 820f) y = 50f + random.nextFloat() * 10f }
            }
            Page("p$n", Page.A4_WIDTH, Page.A4_HEIGHT, Paper.GRID, strokes)
        }
        NotebookFile.write(File(out), Notebook("nb-dense", "Cuaderno", "2026-11-17T00:00:00Z", "2026-11-17T00:00:00Z", pages))
    }

    private companion object {
        /** Nord's dark ink, the pen's plain colour on light paper. */
        const val INK = 0xFF2E3440.toInt()
    }
}
