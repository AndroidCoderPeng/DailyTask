package com.pengxh.kt.lite.extensions

import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.TextSwitcher

/**
 * 上下滚动消息动画扩展
 * */
fun TextSwitcher.setAnimation() {
    val translateIn = TranslateAnimation(0f, 0f, 50f, 0f)
    val alphaIn = AlphaAnimation(0f, 1f)
    val animatorSetIn = AnimationSet(true)
    animatorSetIn.addAnimation(translateIn)
    animatorSetIn.addAnimation(alphaIn)
    animatorSetIn.duration = 1000
    this.inAnimation = animatorSetIn

    val translateOut = TranslateAnimation(0f, 0f, 0f, -50f)
    val alphaOut = AlphaAnimation(1f, 0f)
    val animatorSetOut = AnimationSet(true)
    animatorSetOut.addAnimation(translateOut)
    animatorSetOut.addAnimation(alphaOut)
    animatorSetOut.duration = 1000
    this.outAnimation = animatorSetOut
}