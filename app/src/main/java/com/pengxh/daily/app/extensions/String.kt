package com.pengxh.daily.app.extensions

import com.google.gson.Gson
import com.google.gson.JsonObject

val gson by lazy { Gson() }

/**
 * String扩展方法
 */
fun String.getResponseHeader(): Pair<Int, String> {
    if (this.isBlank()) {
        return Pair(404, "Invalid Response")
    }
    val jsonObject = gson.fromJson(this, JsonObject::class.java)
    val code = jsonObject.get("errcode").asInt
    val message = jsonObject.get("errmsg").asString
    return Pair(code, message)
}