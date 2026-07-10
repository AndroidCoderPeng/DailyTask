package com.pengxh.kt.lite.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/**
 * RecyclerView多选适配器
 *
 * 注意：此方案要求 T 正确实现 equals() 和 hashCode()。如果 T 是数据类（data class），Kotlin 已自动生成。
 */
abstract class MultipleChoiceAdapter<T>(
    private val xmlResource: Int,
    dataRows: MutableList<T>
) : RecyclerView.Adapter<ViewHolder>() {

    private val kTag = "MultipleChoiceAdapter"
    private val dataRows = Collections.synchronizedList(dataRows)

    // 使用 item 作为 key，而非 position
    private val selectedItems = Collections.synchronizedSet(HashSet<T>())

    override fun getItemCount(): Int = dataRows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(xmlResource, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataRows[position]
        convertView(holder, position, item)

        holder.itemView.isSelected = selectedItems.contains(item)
        holder.itemView.setOnClickListener {
            synchronized(selectedItems) {
                if (selectedItems.contains(item)) {
                    selectedItems.remove(item)
                    holder.itemView.isSelected = false
                } else {
                    selectedItems.add(item)
                    holder.itemView.isSelected = true
                }
            }

            itemCheckedListener?.onItemChecked(ArrayList(selectedItems)) ?: run {
                Log.w(kTag, "No listener set for item checked events")
            }
        }
    }

    /**
     * 加载更多
     * */
    fun loadMore(newRows: MutableList<T>) {
        if (newRows.isEmpty()) {
            Log.d(kTag, "loadMore: newRows isEmpty")
            return
        }
        val startPosition = dataRows.size
        val newSize = newRows.size
        synchronized(dataRows) {
            dataRows.addAll(newRows)
        }
        notifyItemRangeInserted(startPosition, newSize)
    }

    abstract fun convertView(viewHolder: ViewHolder, position: Int, item: T)

    private var itemCheckedListener: OnItemCheckedListener<T>? = null

    interface OnItemCheckedListener<T> {
        fun onItemChecked(items: List<T>)
    }

    fun setOnItemCheckedListener(listener: OnItemCheckedListener<T>?) {
        itemCheckedListener = listener
    }
}