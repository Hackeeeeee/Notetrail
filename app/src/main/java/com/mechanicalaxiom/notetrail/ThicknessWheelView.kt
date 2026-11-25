package com.mechanicalaxiom.notetrail

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class ThicknessWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121212")
        strokeWidth = 2f
        textAlign = Paint.Align.CENTER
        textSize = 32f
    }

    private var lastY = 0f
    var thickness: Float = 8f
        set(value) {
            val old = field.roundToInt()
            field = value.coerceIn(1f, 100f)
            val new = field.roundToInt()
            if (old != new) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onThicknessChanged?.invoke(field)
                invalidate()
            }
        }

    var onThicknessChanged: ((Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Draw "Wheel" background
        paint.style = Paint.Style.FILL
        paint.alpha = 30
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f, paint)
        
        // Draw current value
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        canvas.drawText(thickness.roundToInt().toString(), centerX, centerY + 12f, paint)
        
        // Draw markers
        paint.style = Paint.Style.STROKE
        for (i in -2..2) {
            val offset = i * 40f
            val alpha = 255 - (Math.abs(i) * 50)
            paint.alpha = alpha.coerceAtLeast(0)
            canvas.drawLine(10f, centerY + offset, 30f, centerY + offset, paint)
            canvas.drawLine(width - 30f, centerY + offset, width - 10f, centerY + offset, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                thickness -= dy * 0.2f
                lastY = event.y
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
