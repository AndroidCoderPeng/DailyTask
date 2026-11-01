package com.pengxh.daily.app.utils

import java.time.LocalDate

/**
 * 每日重置标记使用的日期键
 *
 * Constant.LAST_RESET_DATE_KEY 由两处读写：
 * 1. TaskScheduler —— 每日任务跑完后写入
 * 2. MainActivity.checkMissedReset() —— 兜底检查时读取
 *
 * 两边必须使用同一个格式化入口，否则"今天"的判断会对不上，
 * 兜底重置会静默失效（表现为每天都不重置或每天都重置）。
 */
fun todayDateKey(): String = LocalDate.now().toString()
