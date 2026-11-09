package com.pengxh.daily.app.adapter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.convertColor
import com.pengxh.daily.app.extensions.resolveRandomTimeRange
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean

class DailyTaskAdapter : RecyclerView.Adapter<ViewHolder>() {
    companion object {
        private const val STATE_PENDING = 0
        private const val STATE_RUNNING = 1
        private const val STATE_DONE = 2
        private const val STATE_SKIPPED = 3
    }

    /**
     * adapter 自持的数据副本
     *
     * 不与外部共享同一个 List 引用，避免外部替换 List 后
     * */
    private val dataBeans = mutableListOf<DailyTaskBean>()

    private var index = -1
    private var lastRunPosition = -1   // 本轮执行推进到的位置，停止后保留
    private val actualTimes = mutableMapOf<Int, String>()
    private val skippedPositions = mutableSetOf<Int>()
    private var onItemClickListener: OnItemClickListener? = null

    fun updateCurrentTaskState(position: Int) {
        val oldPosition = index
        index = position
        if (oldPosition >= 0 && oldPosition < dataBeans.size) notifyItemChanged(oldPosition)
        if (position >= 0 && position < dataBeans.size) notifyItemChanged(position)
    }

    fun updateCurrentTaskState(position: Int, ts: String) {
        if (position < 0 || position >= dataBeans.size) return
        val oldPosition = index
        index = position
        lastRunPosition = position
        actualTimes[position] = ts
        if (oldPosition >= 0 && oldPosition < dataBeans.size && oldPosition != position) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(position)
    }

    fun markSkipped(position: Int) {
        if (position < 0 || position >= dataBeans.size) return
        skippedPositions.add(position)
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
        val state = stateOf(position)

        val taskBean = dataBeans[position]
        val actual = when (state) {
            STATE_SKIPPED -> "未执行"
            else -> actualTimes[position] ?: "--:--:--"
        }
        holder.setText(R.id.taskTimeView, taskBean.time)
            .setText(R.id.randomTimeView, taskBean.resolveRandomTimeRange() ?: "--:--:--")
            .setText(R.id.actualTimeView, actual)
        if (position == index) {
            holder.setTextColor(
                R.id.actualTimeView,
                R.color.success_color.convertColor(holder.itemView.context)
            )
        }

        bindChain(holder, state)
        bindState(holder, state)

        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(position)
        }

        holder.itemView.setOnLongClickListener {
            onItemClickListener?.onItemLongClick(position)
            return@setOnLongClickListener true
        }
    }

    private fun stateOf(position: Int): Int = when {
        position == index -> STATE_RUNNING
        position in skippedPositions && actualTimes[position] == null -> STATE_SKIPPED
        actualTimes.containsKey(position) -> STATE_DONE
        position < index -> STATE_DONE
        index < 0 && lastRunPosition >= 0 && position <= lastRunPosition -> STATE_DONE
        else -> STATE_PENDING
    }

    /**
     * 链式轨道：节点颜色按执行进度推导
     * */
    private fun bindChain(holder: ViewHolder, state: Int) {
        val dot = holder.itemView.findViewById<View>(R.id.chainDotView)
        val context = holder.itemView.context
        val colorRes = when (state) {
            STATE_RUNNING -> R.color.theme_color
            STATE_DONE -> R.color.success_color
            STATE_SKIPPED -> R.color.warning_color
            else -> R.color.text_hint_color
        }
        dot.backgroundTintList = ColorStateList.valueOf(colorRes.convertColor(context))
        bindPulse(dot, state == STATE_RUNNING)
    }

    /**
     * 执行中节点脉冲呼吸；复用 ItemView 时先取消旧动画再按需重启
     * */
    private fun bindPulse(dot: View, running: Boolean) {
        (dot.tag as? ValueAnimator)?.cancel()
        dot.tag = null
        if (!running) {
            dot.scaleX = 1f
            dot.scaleY = 1f
            return
        }
        val animator = ValueAnimator.ofFloat(1f, 1.35f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                dot.scaleX = scale
                dot.scaleY = scale
            }
        }
        dot.tag = animator
        animator.start()
    }

    /**
     * 右上角状态药丸：文案 + 柔光底色与节点同色呼应
     * */
    private fun bindState(holder: ViewHolder, state: Int) {
        val context = holder.itemView.context
        val stateCard = holder.itemView.findViewById<MaterialCardView>(R.id.stateCardView)
        val stateText = holder.itemView.findViewById<TextView>(R.id.stateTextView)

        when (state) {
            STATE_RUNNING -> {
                stateText.text = "执行中"
                stateText.setTextColor(R.color.theme_color.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.theme_soft_color.convertColor(context))
            }

            STATE_DONE -> {
                stateText.text = "已完成"
                stateText.setTextColor(R.color.success_color.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.success_soft_color.convertColor(context))
            }

            STATE_SKIPPED -> {
                stateText.text = "已跳过"
                stateText.setTextColor(R.color.warning_color.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.warning_soft_color.convertColor(context))
            }

            else -> {
                stateText.text = "-"
                stateText.setTextColor(R.color.text_secondary_color.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.back_ground_color.convertColor(context))
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh(newRows: List<DailyTaskBean>) {
        dataBeans.clear()
        dataBeans.addAll(newRows)
        actualTimes.clear()
        skippedPositions.clear()
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