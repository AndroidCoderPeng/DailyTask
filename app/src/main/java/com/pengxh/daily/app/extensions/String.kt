package com.pengxh.daily.app.extensions

import android.content.Context
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject

fun String.show(context: Context) {
    Toast.makeText(context, this, Toast.LENGTH_SHORT).show()
}

/**
 * 匹配邮箱地址
 */
fun String.isEmail(): Boolean {
    return if (isBlank()) {
        false
    } else {
        val regExp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\$"
        val regex = Regex(regExp)
        matches(regex)
    }
}

/**
 * 判断输入的是否是数字
 */
fun String.isNumber(): Boolean {
    val regex = Regex("([-+])?\\d+(\\.\\d+)?")
    return matches(regex)
}

fun String.getResponseHeader(): Pair<Int, String> {
    if (this.isBlank()) {
        return Pair(404, "Invalid Response")
    }
    val jsonObject = Gson().fromJson(this, JsonObject::class.java)
    val code = jsonObject.get("errcode").asInt
    val message = jsonObject.get("errmsg").asString
    return Pair(code, message)
}