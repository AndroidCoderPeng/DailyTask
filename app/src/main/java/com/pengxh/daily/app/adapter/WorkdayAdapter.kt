package com.pengxh.daily.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.show
import java.time.DayOfWeek

class WorkdayAdapter(private val selected: MutableSet<DayOfWeek>) :
    RecyclerView.Adapter<WorkdayAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val chipView: TextView = v.findViewById(R.id.chipView)
    }

    private val days = DayOfWeek.entries // 周一到周日

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_workday_rv_g, parent, false)
        )
    }

    override fun getItemCount() = days.size

    override fun onBindViewHolder(holder: ViewHolder, pos: Int) {
        val day = days[pos]
        holder.chipView.text = "周${"一二三四五六日"[pos]}"
        holder.chipView.isSelected = day in selected   // 用 selected selector 换背景色
        holder.chipView.setOnClickListener {
            if (day in selected && selected.size == 1) {
                // 至少保留一天为工作日
                holder.chipView.context.let { "至少保留一天为工作日".show(it) }
                return@setOnClickListener
            }
            if (day in selected) selected.remove(day) else selected.add(day)
            notifyItemChanged(pos)
        }
    }
}