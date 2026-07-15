package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityCommandBinding
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show

class CommandActivity : KotlinBaseActivity<ActivityCommandBinding>() {

    private val context = this
    private val list = mutableListOf(
        Triple("DT#执行任务", "启动循环任务（默认每天自动执行）", false),
        Triple("DT#终止任务", "停止循环任务（仅停止当天）", true),
        Triple("DT#开启循环", "开启周期循环的标志", true),
        Triple("DT#关闭循环", "关闭循环标志位（永久暂停，直到收到「开启循环」指令）", true),
        Triple("DT#息屏", "开启伪装息屏模式，显示纯黑背景+动态时钟", false),
        Triple("DT#亮屏", "退出伪装息屏模式", false),
        Triple("DT#考勤记录", "导出当天考勤记录", true),
        Triple("DT#打卡", "触发一次打卡（默认指令内容为「打卡」）", true),
        Triple("DT#状态查询", "查询当前状态（任务、服务、监听、电量、版本、日期等）", true),
        Triple("DT#截屏", "截取目标应用屏幕并通过消息渠道返回给用户", true)
    )
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    override fun initViewBinding(): ActivityCommandBinding {
        return ActivityCommandBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val adapter = object : NormalRecyclerAdapter<Triple<String, String, Boolean>>(
            R.layout.item_command_rv_l, list
        ) {
            override fun convertView(
                viewHolder: ViewHolder, position: Int, item: Triple<String, String, Boolean>
            ) {
                viewHolder.setText(R.id.commandView, item.first)
                    .setText(R.id.descView, item.second)
                    .setText(R.id.flagView, if (item.third) "有反馈" else "无反馈")

                val cardView = viewHolder.getView<MaterialCardView>(R.id.cardView)
                if (item.third) {
                    cardView.setCardBackgroundColor(R.color.ios_green.convertColor(context))
                } else {
                    cardView.setCardBackgroundColor(R.color.orange.convertColor(context))
                }
            }
        }
        binding.recyclerView.adapter = adapter
        adapter.setOnItemClickedListener(object :
            NormalRecyclerAdapter.OnItemClickedListener<Triple<String, String, Boolean>> {
            override fun onItemClicked(position: Int, item: Triple<String, String, Boolean>) {
                val cipData = ClipData.newPlainText("RemoteCommand", item.first)
                clipboard.setPrimaryClip(cipData)
                "指令「${item.first}」已复制".show(context)
            }
        })
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {

    }
}