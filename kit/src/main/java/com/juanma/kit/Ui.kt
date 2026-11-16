package com.juanma.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser

/**
 * The look, in one place: Nord, the palette the desktop and the other apps
 * use. Screens are built in code from these pieces; use them instead of
 * styling a view by hand.
 */
object Ui {
    const val BG = 0xFF2E3440.toInt()
    const val SURFACE = 0xFF3B4252.toInt()
    const val RAISED = 0xFF434C5E.toInt()
    const val MUTED = 0xFF4C566A.toInt()
    const val DIM = 0xFF8792A8.toInt()
    const val TEXT = 0xFFD8DEE9.toInt()
    const val BRIGHT = 0xFFECEFF4.toInt()
    const val ACCENT = 0xFF88C0D0.toInt()
    const val RED = 0xFFBF616A.toInt()
    const val GREEN = 0xFFA3BE8C.toInt()
    const val YELLOW = 0xFFEBCB8B.toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun text(context: Context, value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** A module's card: icon and title on top, whatever the module adds below. */
    fun card(context: Context, icon: String, title: String, about: String? = null): LinearLayout {
        val card = column(context)
        card.background = rounded(SURFACE, 20f, context)
        val pad = dp(context, 18f)
        card.setPadding(pad, pad, pad, pad)
        val head = row(context)
        head.addView(View(context).apply { background = Icons.drawable(icon, ACCENT) },
            LinearLayout.LayoutParams(dp(context, 22f), dp(context, 22f)))
        head.addView(text(context, title, 17f, BRIGHT, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 10f) })
        card.addView(head)
        if (about != null) {
            card.addView(text(context, about, 14f, DIM),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8f) })
        }
        return card
    }

    /** A wide pill. [filled] is the "this is on / do this" look; otherwise quiet. */
    fun button(context: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView =
        text(context, label, 15f, if (filled) BG else BRIGHT, bold = true).apply {
            gravity = Gravity.CENTER
            val v = dp(context, 13f)
            setPadding(dp(context, 18f), v, dp(context, 18f), v)
            style(this, filled)
            setOnClickListener { onClick() }
        }

    fun style(button: TextView, filled: Boolean) {
        val context = button.context
        button.setTextColor(if (filled) BG else BRIGHT)
        button.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            rounded(if (filled) ACCENT else RAISED, 14f, context), null,
        )
    }

    /**
     * A round swatch to pick a colour or a pen width: a [sizeDp] dot of [color]
     * centred in a 40dp touch target, with a ring around it when [selected].
     */
    fun swatch(context: Context, color: Int, sizeDp: Float, selected: Boolean, onClick: () -> Unit): View =
        View(context).apply {
            val box = dp(context, 40f)
            val inset = (box - dp(context, sizeDp)) / 2
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected) RAISED else 0)
                if (selected) setStroke(dp(context, 2f), ACCENT)
            }
            background = android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply {
                val edge = dp(context, 2f)
                setLayerInset(0, edge, edge, edge, edge)
                setLayerInset(1, inset, inset, inset, inset)
            }
            layoutParams = LinearLayout.LayoutParams(box, box)
            setOnClickListener { onClick() }
        }

    fun gap(context: Context, heightDp: Float) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }
}

/**
 * Icons drawn from 24x24 path data (Material Icons, Apache 2.0), so an icon is
 * one line here instead of an app asset.
 */
object Icons {
    private val paths = mapOf(
        "share" to "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z",
        "download" to "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
        "pen" to "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        "dot" to "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z",
    )

    fun drawable(name: String, color: Int): Drawable =
        PathDrawable(PathParser.createPathFromPathData(paths[name] ?: paths.getValue("dot")), color)

    private class PathDrawable(private val source: Path, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val scaled = Path()
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val box = bounds
            matrix.setScale(box.width() / 24f, box.height() / 24f)
            matrix.postTranslate(box.left.toFloat(), box.top.toFloat())
            source.transform(matrix, scaled)
            canvas.drawPath(scaled, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
