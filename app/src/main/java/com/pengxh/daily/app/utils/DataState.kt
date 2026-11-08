package com.pengxh.daily.app.utils

sealed class DataState {
    data object Loading : DataState()

    /**
     * 当年数据可用
     * @param syncedAt 状态生成时间，保证每次成功同步都是一个新的状态值
     */
    data class Ready(
        val syncedAt: Long = System.currentTimeMillis()
    ) : DataState()

    /**
     * 当年数据不可用（无缓存且拉取失败）
     * */
    data object Unavailable : DataState()
}