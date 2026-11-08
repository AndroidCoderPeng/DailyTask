package com.pengxh.daily.app.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R

/**
 * 链式任务列表的连线：从上一个节点的中心，一直画到下一个节点的中心。
 * */
class ChainLineDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density

    /**
     * 轨道列中心 x 偏移（相对 item 左边缘）
     * */
    private val trackOffset = context.resources.getDimension(R.dimen.chain_track_width) / 2

    /**
     * 节点中心 y 偏移（相对 item 顶部）
     * */
    private val dotCenter = context.resources.getDimension(R.dimen.chain_dot_center)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1 * density
        color = "#FFDFDFDF".toColorInt()
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count < 2) return

        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(next)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (nextPos != pos + 1) continue // 相邻两屏内 item 不连续时跳过

            val x = child.left + trackOffset
            canvas.drawLine(x, child.top + dotCenter, x, next.top + dotCenter, paint)
        }
    }
}