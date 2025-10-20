package com.pengxh.daily.app.utils

data class EmailConfig(
    var emailSender: String = "",
    var authCode: String = "",
    var senderServer: String = "",
    var emailPort: String = "",
    var inboxEmail: String = "",
    var emailTitle: String = ""
)