package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityNoticeBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemDivider
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.show

class NoticeRecordActivity : KotlinBaseActivity<ActivityNoticeBinding>() {

    private var noticeAdapter: NormalRecyclerAdapter<NotificationBean>? = null

    override fun initViewBinding(): ActivityNoticeBinding {
        return ActivityNoticeBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.menu_clear_history) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("清空通知")
                    .setMessage("此操作将会清空所有通知记录，且不可恢复。\n\n是否清空？")
                    .setCancelable(false) // 禁止点击外部关闭
                    .setPositiveButton("确认清空") { _, _ ->
                        DatabaseWrapper.deleteAllNotice()
                        binding.emptyView.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                        "通知记录已全部清空！".show(this)
                    }.setNegativeButton("取消操作", null).show()
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val (startDate, endDate) = TimeKit.getWeekDateRange()
        val dataBeans = DatabaseWrapper.loadWeeklyNotice(startDate, endDate)
        if (dataBeans.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            noticeAdapter = object : NormalRecyclerAdapter<NotificationBean>(
                R.layout.item_notice_rv_l, dataBeans
            ) {
                override fun convertView(
                    viewHolder: ViewHolder, position: Int, item: NotificationBean
                ) {
                    viewHolder.setText(R.id.titleView, item.noticeTitle)
                        .setText(R.id.packageNameView, item.packageName)
                        .setText(R.id.messageView, item.noticeMessage)
                        .setText(R.id.postTimeView, item.postTime)
                }
            }
            binding.recyclerView.addItemDecoration(RecyclerViewItemDivider(0f, 0f, Color.LTGRAY))
            binding.recyclerView.adapter = noticeAdapter
        }
    }

    override fun initEvent() {

    }

    override fun observeRequestState() {

    }
}