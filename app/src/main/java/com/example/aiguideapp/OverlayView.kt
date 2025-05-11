package com.example.aiguideapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
//import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
        color = 0xffff00ff.toInt()  // 보라색 점
    }

    // normalized center points [0..1]
    var centers: List<PointF> = emptyList()
        set(v) { field = v; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (p in centers) {
            canvas.drawCircle(p.x * w, p.y * h, 12f, paint)
        }
    }
}
