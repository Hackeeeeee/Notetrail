package com.mechanicalaxiom.notetrail

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorSpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var spectrumGradient: LinearGradient? = null
    
    var onColorSelected: ((Int) -> Unit)? = null

    private val colors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val isVertical = h > w
        spectrumGradient = if (isVertical) {
            LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                colors, null, Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                colors, null, Shader.TileMode.CLAMP
            )
        }
        spectrumPaint.shader = spectrumGradient
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), spectrumPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val isVertical = height > width
                val color = if (isVertical) {
                    val y = event.y.coerceIn(0f, height.toFloat() - 1)
                    getColorAtY(y)
                } else {
                    val x = event.x.coerceIn(0f, width.toFloat() - 1)
                    getColorAtX(x)
                }
                onColorSelected?.invoke(color)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getColorAtX(x: Float): Int {
        val fraction = x / width
        return getColorAtFraction(fraction)
    }

    private fun getColorAtY(y: Float): Int {
        val fraction = y / height
        return getColorAtFraction(fraction)
    }

    private fun getColorAtFraction(fraction: Float): Int {
        val count = colors.size - 1
        val index = (fraction * count).toInt().coerceIn(0, count - 1)
        val innerFraction = (fraction * count) - index
        
        val startColor = colors[index]
        val endColor = colors[index + 1]
        
        return interpolateColor(startColor, endColor, innerFraction)
    }

    private fun interpolateColor(a: Int, b: Int, fraction: Float): Int {
        val hsvA = FloatArray(3)
        val hsvB = FloatArray(3)
        Color.colorToHSV(a, hsvA)
        Color.colorToHSV(b, hsvB)
        
        val hsv = FloatArray(3)
        for (i in 0..2) {
            hsv[i] = hsvA[i] + (hsvB[i] - hsvA[i]) * fraction
        }
        return Color.HSVToColor(hsv)
    }
}
