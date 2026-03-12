package com.pengxh.daily.app.extensions

import android.content.Context
import android.os.BatteryManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pengxh.daily.app.BuildConfig
import com.pengxh.kt.lite.extensions.getSystemService
import com.pengxh.kt.lite.extensions.timestampToDate

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

fun String.buildContent(context: Context): String {
    val baseContent = if (this.isBlank()) {
        "未监听到打卡成功的通知，请手动登录检查 ${System.currentTimeMillis().timestampToDate()}"
    } else {
        "$this，版本号：${BuildConfig.VERSION_NAME}"
    }

    val batteryCapacity = context.getSystemService<BatteryManager>()
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    return "$baseContent，当前手机剩余电量为：${if (batteryCapacity >= 0) "$batteryCapacity%" else "未知"}"
}