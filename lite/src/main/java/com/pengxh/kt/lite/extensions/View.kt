package com.pengxh.kt.lite.extensions

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

fun View.expand(duration: Long = 350) {
    // 先测量真实高度
    measure(
        View.MeasureSpec.makeMeasureSpec((parent as View).width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val targetHeight = measuredHeight
    layoutParams.height = 0
    visibility = View.VISIBLE
    alpha = 0f

    ValueAnimator.ofInt(0, targetHeight).apply {
        this.duration = duration
        interpolator = DecelerateInterpolator()
        addUpdateListener { animator ->
            layoutParams.height = animator.animatedValue as Int
            requestLayout()
            alpha = animator.animatedFraction
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                alpha = 1f
            }
        })
        start()
    }
}

fun View.collapse(duration: Long = 350) {
    val initialHeight = measuredHeight

    ValueAnimator.ofInt(initialHeight, 0).apply {
        this.duration = duration
        interpolator = DecelerateInterpolator()
        addUpdateListener { animator ->
            layoutParams.height = animator.animatedValue as Int
            requestLayout()
            alpha = 1f - animator.animatedFraction
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                visibility = View.GONE
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                alpha = 1f
            }
        })
        start()
    }
}