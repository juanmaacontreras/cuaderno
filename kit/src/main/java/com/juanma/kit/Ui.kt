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
    // Nord's deeper tones: they read on a light surface as well as a dark one.
    const val ACCENT_DEEP = 0xFF5E81AC.toInt()
    const val GREEN_DEEP = 0xFF7FA05F.toInt()
    const val ORANGE = 0xFFD08770.toInt()

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

    /**
     * A square tool button with an icon from [Icons]: filled when it is the
     * tool in use ([on]), faint when it cannot be used now ([dim]).
     */
    fun tool(context: Context, icon: String, on: Boolean = false, dim: Boolean = false, onClick: () -> Unit): View =
        View(context).apply {
            val glyph = Icons.drawable(icon, if (on) BG else if (dim) MUTED else TEXT)
            val inset = dp(context, 11f)
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(if (on) ACCENT else 0, 12f, context), glyph)).apply {
                setLayerInset(1, inset, inset, inset, inset)
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 44f), dp(context, 44f))
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
        "undo" to "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
        "redo" to "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z",
        "more" to "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
        "back" to "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
        "highlighter" to "M6 14l3 3v5h6v-5l3-3V9H6v5zm5-12h2v3h-2V2zM3.5 5.88l1.41-1.41 2.12 2.12L5.62 8 3.5 5.88zm13.46.71l2.12-2.12 1.41 1.41L18.38 8l-1.42-1.41z",
        // Drawn for this kit: an eraser block and a lasso loop.
        "eraser" to "M15.1 3.3 21 9.2c.8.8.8 2 0 2.8L12.2 20.8H21v2H5.6l-3-3c-.8-.8-.8-2 0-2.8L12.3 3.3c.8-.8 2-.8 2.8 0zM4 18.4 6.4 20.8h3L12 18.2 7.4 13.6 4 17z",
        "lasso" to "M12 3C6.5 3 2 5.9 2 9.5c0 2.3 1.8 4.300 4.500 5.400L5.500 21l2 .400 1-5.800c1.100.200 2.300.400 3.500.400 5.500 0 10-2.900 10-6.500S17.500 3 12 3zm0 11c-4.400 0-8-2-8-4.500S7.600 5 12 5s8 2 8 4.500-3.600 4.500-8 4.500z",
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
