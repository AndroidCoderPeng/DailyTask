package com.pengxh.kt.lite.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections


/**
 * RecyclerView单选适配器
 */
abstract class SingleChoiceAdapter<T>(
    private val xmlResource: Int,
    dataRows: MutableList<T>
) : RecyclerView.Adapter<ViewHolder>() {

    private val kTag = "SingleChoiceAdapter"
    private val dataRows = Collections.synchronizedList(dataRows)

    // 选中的 item，而非 position
    private var selectedItem: T? = null

    fun setSelectedItem(item: T) {
        selectedItem = item
    }

    override fun getItemCount(): Int = dataRows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(xmlResource, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataRows[position]
        convertView(holder, position, item)

        // 根据 item 是否等于选中的 item 设置状态
        val isSelected = item == selectedItem
        holder.itemView.isSelected = isSelected

        holder.itemView.setOnClickListener {
            if (item != selectedItem) {
                val oldItem = selectedItem
                selectedItem = item

                // 刷新旧选中的 item（如果还在列表中）
                oldItem?.let { old ->
                    val oldPosition = dataRows.indexOf(old)
                    if (oldPosition != -1) {
                        notifyItemChanged(oldPosition)
                    }
                }

                // 刷新新选中的 item
                notifyItemChanged(position)

                itemCheckedListener?.onItemChecked(position, item)
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
        fun onItemChecked(position: Int, item: T)
    }

    fun setOnItemCheckedListener(listener: OnItemCheckedListener<T>?) {
        itemCheckedListener = listener
    }
}