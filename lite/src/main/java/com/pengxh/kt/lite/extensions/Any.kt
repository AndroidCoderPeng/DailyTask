package com.pengxh.kt.lite.extensions

import com.google.gson.Gson

fun Any.toJson(): String = Gson().toJson(this)