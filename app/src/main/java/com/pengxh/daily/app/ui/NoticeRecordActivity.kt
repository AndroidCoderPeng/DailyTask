package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.R
import com.pengxh.daily.app.bean.NotificationBean
import com.pengxh.daily.app.databinding.ActivityNoticeBinding
import com.pengxh.daily.app.extensions.initImmersionBar
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemDivider
import com.pengxh.kt.lite.widget.TitleBarView
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog

class NoticeRecordActivity : KotlinBaseActivity<ActivityNoticeBinding>() {

    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }
    private lateinit var noticeAdapter: NormalRecyclerAdapter<NotificationBean>
    private var isRefresh = false
    private var isLoadMore = false
    private var offset = 1

    override fun initViewBinding(): ActivityNoticeBinding {
        return ActivityNoticeBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        binding.rootView.initImmersionBar(this, true, R.color.white)
        binding.titleView.setOnClickListener(object : TitleBarView.OnClickListener {
            override fun onLeftClick() {
                finish()
            }

            override fun onRightClick() {
                AlertMessageDialog.Builder()
                    .setContext(this@NoticeRecordActivity)
                    .setTitle("温馨提示")
                    .setMessage("此操作将会清空所有通知记录，且不可恢复")
                    .setPositiveButton("知道了")
                    .setOnDialogButtonClickListener(object :
                        AlertMessageDialog.OnDialogButtonClickListener {
                        override fun onConfirmClick() {
                            noticeDao.deleteAll()
                            binding.emptyView.visibility = View.VISIBLE
                            binding.recyclerView.visibility = View.GONE
                        }
                    }).build().show()
            }
        })
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val dataBeans = getNotificationRecord()
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
                    viewHolder.setText(R.id.titleView, "标题：${item.notificationTitle}")
                        .setText(R.id.packageNameView, "包名：${item.packageName}")
                        .setText(R.id.messageView, "内容：${item.notificationMsg}")
                        .setText(R.id.postTimeView, item.postTime)
                }
            }
            binding.recyclerView.addItemDecoration(RecyclerViewItemDivider(0f, 0f, Color.LTGRAY))
            binding.recyclerView.adapter = noticeAdapter
        }
    }

    override fun initEvent() {
        binding.refreshLayout.setOnRefreshListener {
            isRefresh = true
            offset = 0
            object : CountDownTimer(1000, 500) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    it.finishRefresh()
                    isRefresh = false
                    noticeAdapter.refresh(getNotificationRecord())
                }
            }.start()
        }

        binding.refreshLayout.setOnLoadMoreListener {
            isLoadMore = true
            offset++
            object : CountDownTimer(1000, 500) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    it.finishLoadMore()
                    isLoadMore = false
                    noticeAdapter.loadMore(getNotificationRecord())
                }
            }.start()
        }
    }

    override fun observeRequestState() {

    }

    private fun getNotificationRecord(): MutableList<NotificationBean> {
        return noticeDao.loadNoticeByTime(10, (offset - 1) * 10)
    }
}