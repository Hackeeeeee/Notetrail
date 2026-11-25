package com.mechanicalaxiom.notetrail

import android.graphics.Color

data class AnnotationPageData(
    val pages: Map<String, PageAnnotations> = emptyMap(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class PageAnnotations(
    val strokes: List<StrokeData> = emptyList(),
    val texts: List<TextAnnotationData> = emptyList(),
    val shapes: List<ShapeAnnotationData> = emptyList()
)

data class ShapeAnnotationData(
    val type: String = "RECT", // RECT, CIRCLE, LINE
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val color: Int = Color.BLACK,
    val width: Float = 5f,
    val timestamp: Long = System.currentTimeMillis()
)

data class TextAnnotationData(
    val content: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val color: Int = Color.BLACK,
    val fontSize: Float = 16f,
    val timestamp: Long = System.currentTimeMillis()
)

data class StrokeData(
    val points: List<NormalizedPoint> = emptyList(),
    val color: Int = Color.BLACK,
    val width: Float = 5f,
    val isHighlighter: Boolean = false,
    val isEraser: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class NormalizedPoint(
    val x: Float = 0f, // 0.0 to 1.0
    val y: Float = 0f  // 0.0 to 1.0
)
