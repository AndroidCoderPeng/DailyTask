package com.pengxh.kt.lite.divider

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 设置 [androidx.recyclerview.widget.RecyclerView] Item外边距，也可以代替设置上下左右分割线
 * */
class RecyclerViewItemBorder(
    private val left: Int, private val top: Int, private val right: Int, private val bottom: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.left = left
        outRect.top = top
        outRect.right = right
        outRect.bottom = bottom
    }
}