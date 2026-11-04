package com.pengxh.daily.app.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.convertColor
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean

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
            .setText(R.id.actualTimeView, actualTime)
        if (position == mPosition) {
            holder.setTextColor(
                R.id.actualTimeView,
                R.color.success_color.convertColor(holder.itemView.context)
            )
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