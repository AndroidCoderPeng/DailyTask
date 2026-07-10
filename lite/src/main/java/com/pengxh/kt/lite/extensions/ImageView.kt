package com.pengxh.kt.lite.extensions

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable

fun ImageView.switchBackground(blurBitmap: Bitmap?) {
    if (blurBitmap == null) return

    val lastDrawable: Drawable = when (val currentDrawable = drawable) {
        is TransitionDrawable -> {
            // 获取当前TransitionDrawable的前景drawable作为上一个背景
            currentDrawable.getDrawable(1) ?: Color.TRANSPARENT.toDrawable()
        }

        is BitmapDrawable -> currentDrawable
        null -> Color.TRANSPARENT.toDrawable()
        else -> Color.TRANSPARENT.toDrawable()
    }

    val newDrawable = blurBitmap.toDrawable(resources)

    val transitionDrawable = if (drawable is TransitionDrawable) {
        drawable as TransitionDrawable
    } else {
        TransitionDrawable(arrayOf(lastDrawable, newDrawable)).apply {
            setIds()
            isCrossFadeEnabled = true
            setImageDrawable(this)
        }
    }

    if (drawable === transitionDrawable) {
        transitionDrawable.setDrawableByLayerId(transitionDrawable.getId(1), newDrawable)
        transitionDrawable.startTransition(1000)
    }
}

private fun TransitionDrawable.setIds() {
    setId(0, 0)
    setId(1, 1)
}