package com.pengxh.kt.lite.divider

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 设置 [androidx.recyclerview.widget.RecyclerView] Item的分割线
 * */
class RecyclerViewItemDivider(
    private val leftMargin: Float, private val rightMargin: Float, color: Int
) : RecyclerView.ItemDecoration() {

    private val dividerPaint by lazy {
        Paint().apply {
            this.color = color
            this.strokeWidth = 1f
            this.isAntiAlias = true
        }
    }

    // 为分割线预留 1px 空间，避免内容重叠
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.set(0, 0, 0, 1)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val view = parent.getChildAt(i)
            c.drawLine(
                leftMargin,
                view.bottom.toFloat(),
                view.width - rightMargin,
                view.bottom.toFloat(),
                dividerPaint
            )
        }
    }
}