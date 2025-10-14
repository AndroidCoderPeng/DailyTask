package com.pengxh.daily.app.utils

import com.pengxh.daily.app.model.EmailConfigModel
import com.pengxh.kt.lite.utils.SaveKeyValues

object EmailConfigKit {
    fun getConfig(): EmailConfigModel {
        val emailSender = SaveKeyValues.getValue(Constant.EMAIL_SEND_ADDRESS_KEY, "") as String
        val authCode = SaveKeyValues.getValue(Constant.EMAIL_SEND_CODE_KEY, "") as String
        val senderServer = SaveKeyValues.getValue(Constant.EMAIL_SEND_SERVER_KEY, "") as String
        val emailPort = SaveKeyValues.getValue(Constant.EMAIL_SEND_PORT_KEY, "") as String
        val inboxEmail = SaveKeyValues.getValue(Constant.EMAIL_IN_BOX_KEY, "") as String
        val emailTitle = SaveKeyValues.getValue(Constant.EMAIL_TITLE_KEY, "打卡结果通知") as String
        return EmailConfigModel(
            emailSender, authCode, senderServer, emailPort, inboxEmail, emailTitle
        )
    }

    fun isEmailConfigured(): Boolean {
        val config = getConfig()
        return config.emailSender.isNotEmpty() &&
                config.authCode.isNotEmpty() &&
                config.senderServer.isNotEmpty() &&
                config.emailPort.isNotEmpty() &&
                config.inboxEmail.isNotEmpty()
    }
}