package com.pengxh.daily.app.utils

import javax.mail.Authenticator
import javax.mail.PasswordAuthentication

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2020/1/16 15:42
 */
class EmailAuthenticator(private val username: String, private val authCode: String) :
    Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication {
        return PasswordAuthentication(username, authCode)
    }
}