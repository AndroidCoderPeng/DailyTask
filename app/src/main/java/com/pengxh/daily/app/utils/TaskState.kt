package com.pengxh.daily.app.utils

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.pengxh.daily.app.R

/**
 * 单个任务的执行状态
 *
 * 列表里的状态徽章直接用这里的文案和配色，
 * 免得在 Adapter 里堆一堆 when 分支去拼颜色。
 * */
enum class TaskState(val label: String) {

    /**
     * 还没轮到
     * */
    NOT_STARTED("未开始"),

    /**
     * 倒计时中 / 打卡中
     * */
    EXECUTING("执行中"),

    /**
     * 打卡成功
     * */
    SUCCESS("已完成"),

    /**
     * 超时未检测到打卡
     * */
    FAILED("失败"),

    /**
     * 已过期 / 截屏授权失效 / 今日非工作日
     * */
    SKIPPED("已跳过");

    /**
     * 徽章文字颜色
     * */
    fun textColor(context: Context): Int = when (this) {
        NOT_STARTED -> context.getColor(R.color.text_secondary_color)
        EXECUTING -> context.getColor(R.color.theme_color)
        SUCCESS -> context.getColor(R.color.success_color)
        FAILED -> context.getColor(R.color.failure_color)
        SKIPPED -> context.getColor(R.color.text_hint_color)
    }

    /**
     * 徽章背景色
     *
     * 有色彩的状态用主色 12% 透明度的软背景，
     * 没开始/已跳过这种中性态直接用页面灰底。
     * */
    fun backgroundColor(context: Context): Int = when (this) {
        NOT_STARTED, SKIPPED -> context.getColor(R.color.back_ground_color)
        EXECUTING -> softColor(context, R.color.theme_color)
        SUCCESS -> softColor(context, R.color.success_color)
        FAILED -> softColor(context, R.color.failure_color)
    }

    private fun softColor(context: Context, colorRes: Int): Int {
        return ColorUtils.setAlphaComponent(context.getColor(colorRes), 0x1F)
    }
}
