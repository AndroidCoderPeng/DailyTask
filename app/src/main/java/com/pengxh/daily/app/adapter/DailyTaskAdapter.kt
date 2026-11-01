package com.pengxh.daily.app.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.collapse
import com.pengxh.daily.app.extensions.expand
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.extensions.convertColor

@SuppressLint("NotifyDataSetChanged")
class DailyTaskAdapter : RecyclerView.Adapter<ViewHolder>() {

    /**
     * adapter 自持的数据副本
     *
     * 不与外部共享同一个 List 引用，避免外部替换 List 后
     * "页面数据源" 与 "adapter 数据源" 变成两个对象，
     * 一旦改成增量更新就会直接错位。
     * */
    private val dataBeans = mutableListOf<DailyTaskBean>()

    var mPosition = -1
    private var actualTime = "--:--:--"
    private var onItemClickListener: OnItemClickListener? = null

    fun updateCurrentTaskState(position: Int) {
        val oldPosition = mPosition
        mPosition = position
        if (oldPosition >= 0 && oldPosition < dataBeans.size) notifyItemChanged(oldPosition)
        if (position >= 0 && position < dataBeans.size) notifyItemChanged(position)
    }

    fun updateCurrentTaskState(position: Int, actualTime: String) {
        if (position < 0 || position >= dataBeans.size) return
        val oldPosition = mPosition
        mPosition = position
        this.actualTime = actualTime
        if (oldPosition >= 0 && oldPosition < dataBeans.size && oldPosition != position) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(position)
    }

    override fun getItemCount(): Int = dataBeans.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(
            R.layout.item_daily_task_rv_l, parent, false
        )
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val taskBean = dataBeans[position]
        holder.setText(R.id.taskTimeView, taskBean.time)
        val arrowView = holder.getView<AppCompatImageView>(R.id.arrowView)
        val actualTimeCardView = holder.getView<LinearLayout>(R.id.actualTimeCardView)
        if (position == mPosition) {
            holder.itemView.isSelected = true
            val context = holder.itemView.context
            holder.setText(R.id.actualTimeView, actualTime)
                .setTextColor(R.id.actualTimeView, R.color.theme_color.convertColor(context))
                .setTextColor(R.id.taskTimeView, R.color.text_hint_color.convertColor(context))
            arrowView.animate().rotation(90f).setDuration(350).start()
            if (!actualTimeCardView.isVisible) {
                actualTimeCardView.expand()
            }
        } else {
            holder.itemView.isSelected = false
            holder.setText(R.id.actualTimeView, "--:--:--")
                .setTextColor(R.id.taskTimeView, Color.BLACK)
            arrowView.animate().rotation(0f).setDuration(350).start()
            if (actualTimeCardView.isVisible) {
                actualTimeCardView.collapse()
            }
        }

        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(position)
        }

        holder.itemView.setOnLongClickListener {
            onItemClickListener?.onItemLongClick(position)
            return@setOnLongClickListener true
        }
    }

    fun refresh(newRows: List<DailyTaskBean>) {
        dataBeans.clear()
        dataBeans.addAll(newRows)
        notifyDataSetChanged()
    }

    /**
     * 当前列表快照，供外部按索引取用时保持与展示顺序一致
     * */
    fun itemAt(position: Int): DailyTaskBean? = dataBeans.getOrNull(position)

    interface OnItemClickListener {
        fun onItemClick(position: Int)

        fun onItemLongClick(position: Int)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }
}