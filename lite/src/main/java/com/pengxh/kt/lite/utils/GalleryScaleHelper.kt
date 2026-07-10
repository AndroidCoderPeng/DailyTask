package com.pengxh.kt.lite.utils

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs


class GalleryScaleHelper : RecyclerView.OnScrollListener() {

    private val snapHelper by lazy { PagerSnapHelper() }
    private val pagePadding = 15
    private val leftCardShowWidth = 15
    private var cardWidth = 0
    private var currentItemOffset = 0
    private var currentItemPos = 0
    private var scale = 0.5f

    fun attachToRecyclerView(recyclerView: RecyclerView, scale: Float) {
        this.scale = scale
        val viewTreeObserver = recyclerView.viewTreeObserver
        val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (recyclerView.width > 0) {
                    recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    cardWidth = recyclerView.width - 2 * (pagePadding + leftCardShowWidth)
                }
            }
        }
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        recyclerView.addOnScrollListener(this)
        snapHelper.attachToRecyclerView(recyclerView)
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
        if (dx != 0 && cardWidth > 0) {
            currentItemOffset += dx
            val itemCount = recyclerView.adapter?.itemCount ?: 0
            val scaleClamped = scale.coerceIn(0f..1f)

            val layoutManager = recyclerView.layoutManager ?: return

            // 更新当前项位置
            currentItemPos = (currentItemOffset / cardWidth).coerceIn(0, itemCount - 1)
            val offset = currentItemOffset - currentItemPos * cardWidth
            val percent = minOf(maxOf(abs(offset).toFloat() / cardWidth, 0.0001f), 1.0f)

            val currentView = layoutManager.findViewByPosition(currentItemPos)
            val leftView = if (currentItemPos > 0)
                layoutManager.findViewByPosition(currentItemPos - 1)
            else null

            val rightView = if (currentItemPos < itemCount - 1)
                layoutManager.findViewByPosition(currentItemPos + 1)
            else null

            applyScale(leftView, (1 - scaleClamped) * percent + scaleClamped)
            applyScale(currentView, (scaleClamped - 1) * percent + 1)
            applyScale(rightView, (1 - scaleClamped) * percent + scaleClamped)
        }
    }

    private fun applyScale(view: View?, factor: Float) {
        view?.apply {
            scaleX = factor
            scaleY = factor
        }
    }

    fun getCurrentIndex(): Int {
        return currentItemPos
    }
}