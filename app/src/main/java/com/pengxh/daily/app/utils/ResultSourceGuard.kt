package com.pengxh.daily.app.utils

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.notificationEnable

/**
 * 结果来源配置守卫
 *
 * 无人值守场景下不依赖设置页 onResume，负责把「结果来源」与
 * 「目标应用 + 服务真实能力」校正到一致：
 *  - 非钉钉：只能用截屏，截屏不可用则置为未选择
 *  - 钉钉：保留用户已选的有效来源，失效时降级到另一条可用通道
 *
 * @author: Pengxh
 */
object ResultSourceGuard {
    fun reconcile(): Int {
        val targetApp = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
        val current = AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val notificationReady = DailyTaskApplication.get().notificationEnable()
        val captureReady = ProjectionSession.isStateActive()

        val fixed = when {
            // 非钉钉：结果只能由截屏获取
            targetApp != 0 -> if (captureReady) 1 else Constant.DEFAULT_INDEX

            // 钉钉：优先保留用户的有效选择，失效再降级
            current == 0 && notificationReady -> 0
            current == 1 && captureReady -> 1
            notificationReady -> 0
            captureReady -> 1
            else -> Constant.DEFAULT_INDEX
        }

        if (fixed != current) {
            AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, fixed)
        }
        return fixed
    }
}