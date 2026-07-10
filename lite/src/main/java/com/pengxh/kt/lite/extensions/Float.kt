package com.pengxh.kt.lite.extensions

import android.content.Context
import android.util.TypedValue

/**
 * px转dp
 */
fun Float.px2dp(context: Context): Float {
    return this / context.getScreenDensity()
}

/**
 * dp转px
 */
fun Float.dp2px(context: Context): Float {
    return this * context.getScreenDensity()
}

/**
 * sp转px
 */
fun Float.sp2px(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, this, context.resources.displayMetrics
    )
}
