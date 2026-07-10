package com.pengxh.kt.lite.extensions

/**
 * ByteArray转Hex
 * */
fun ByteArray.toHex(): String {
    val result = StringBuilder(this.size * 2)
    for (j in this.indices) {
        val i = this[j].toInt() and 0xFF
        result.append("0123456789ABCDEF"[i shr 4])
        result.append("0123456789ABCDEF"[i and 0x0F])
    }
    return String(result)
}

/**
 * ByteArray转ASCII
 * */
fun ByteArray.toAscIICode(): String {
    val builder = StringBuilder()
    for (byte in this) {
        val charValue = byte.toInt() and 0xFF
        // 只转换可打印的ASCII字符 (32-126)，其他字符忽略
        if (charValue in 32..126) {
            builder.append(Char(charValue))
        }
    }
    return builder.toString()
}