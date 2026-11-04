package com.pengxh.daily.app.extensions

import android.content.Context
import android.util.TypedValue

/**
 * sp转px
 */
fun Float.sp2px(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, this, context.resources.displayMetrics
    )
}