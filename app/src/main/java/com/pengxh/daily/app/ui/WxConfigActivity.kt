package com.pengxh.daily.app.ui

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityWxConfigBinding
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog

class WxConfigActivity : KotlinBaseActivity<ActivityWxConfigBinding>() {

    private val context = this
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }

    override fun initViewBinding(): ActivityWxConfigBinding {
        return ActivityWxConfigBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) { // 16
            binding.toolbar.setPadding(0, getStatusBarHeight(), 0, 0)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.menu_right) {
                val key = binding.wxKeyView.text.toString()
                if (key.isBlank()) {
                    "企业微信消息 Webhook key 为空".show(context)
                    return@setOnMenuItemClickListener true
                }

                SaveKeyValues.putValue(Constant.WX_WEB_HOOK_KEY, key)

                AlertControlDialog.Builder()
                    .setContext(this)
                    .setTitle("温馨提醒")
                    .setMessage("企业微信配置完成，是否发送测试消息？")
                    .setNegativeButton("取消")
                    .setPositiveButton("好的").setOnDialogButtonClickListener(object :
                        AlertControlDialog.OnDialogButtonClickListener {
                        override fun onCancelClick() {

                        }

                        override fun onConfirmClick() {
                            sendTestMessage()
                        }
                    }).build().show()

            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val key = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
        if (!key.isBlank()) {
            binding.wxKeyView.setText(key)
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {

    }

    private fun sendTestMessage() {
        messageViewModel.sendMessage(
            "你好！这是来自 DailyTask 的测试消息 🎉",
            onLoading = {
                LoadingDialog.show(this, "消息发送中，请稍后...")
            },
            onSuccess = {
                LoadingDialog.dismiss()
            },
            onFailed = {
                LoadingDialog.dismiss()
                it.show(this)
            })
    }
}