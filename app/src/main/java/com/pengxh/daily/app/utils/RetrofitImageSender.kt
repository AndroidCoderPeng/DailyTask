package com.pengxh.daily.app.utils

import android.content.Context
import android.util.Log
import com.pengxh.daily.app.extensions.getResponseHeader
import com.pengxh.daily.app.retrofit.RetrofitServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object RetrofitImageSender {
    private const val TAG = "RetrofitImageSender"
    private val senderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun send(context: Context, imagePath: String) {
        val appContext = context.applicationContext
        senderScope.launch {
            try {
                val response = RetrofitServiceManager.sendImageMessage(imagePath)
                val header = response.getResponseHeader()
                if (header.first != 0) {
                    HttpRequestManager(appContext).sendMessage("截屏失败", header.second)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send image failed", e)
                HttpRequestManager(appContext).sendMessage("截屏失败", e.message ?: "图片发送失败")
            }
        }
    }
}
