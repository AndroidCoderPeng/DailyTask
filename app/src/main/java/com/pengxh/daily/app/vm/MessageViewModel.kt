package com.pengxh.daily.app.vm

import androidx.lifecycle.ViewModel
import com.pengxh.daily.app.extensions.getResponseHeader
import com.pengxh.daily.app.retrofit.RetrofitServiceManager
import com.pengxh.kt.lite.extensions.launch

class MessageViewModel : ViewModel() {
    fun sendMessage(
        content: String,
        onLoading: () -> Unit,
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit
    ) = launch({
        onLoading()
        val response = RetrofitServiceManager.sendMessage(content)
        val header = response.getResponseHeader()
        if (header.first == 0) {
            onSuccess()
        } else {
            onFailed(header.second)
        }
    }, {
        it.printStackTrace()
        onFailed(it.message ?: "Unknown error")
    })
}