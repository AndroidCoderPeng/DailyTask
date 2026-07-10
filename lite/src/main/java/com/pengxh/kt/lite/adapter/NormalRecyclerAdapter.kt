package com.pengxh.kt.lite.adapter

import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * RecyclerView普通列表适配器
 */
abstract class NormalRecyclerAdapter<T>(
    private val xmlResource: Int,
    dataRows: MutableList<T>
) : RecyclerView.Adapter<ViewHolder>() {

    private val kTag = "NormalRecyclerAdapter"
    private val dataRows = Collections.synchronizedList(dataRows)

    private var coroutineScope: LifecycleCoroutineScope? = null

    fun setCoroutineScope(scope: LifecycleCoroutineScope) {
        this.coroutineScope = scope
    }

    override fun getItemCount(): Int = dataRows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(xmlResource, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < 0 || position >= dataRows.size) {
            Log.w(kTag, "onBindViewHolder: invalid position=$position, size=${dataRows.size}")
            return
        }
        val item = dataRows[position]
        convertView(holder, position, item)
        holder.itemView.setOnClickListener {
            // 点击时重新获取当前 position 对应的 item
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION && currentPosition < dataRows.size) {
                itemClickedListener?.onItemClicked(currentPosition, dataRows[currentPosition])
            }
        }
    }

    /**
     * 刷新列表，局部刷新
     * */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh(newRows: MutableList<T>, itemComparator: ItemComparator<T>? = null) {
        if (newRows.isEmpty()) {
            Log.d(kTag, "refresh: newRows isEmpty")
            return
        }

        val oldSize = dataRows.size

        if (itemComparator != null && coroutineScope != null) {
            val oldDataSnapshot = ArrayList(dataRows) // 旧数据副本
            val newDataSnapshot = ArrayList(newRows)  // 新数据副本

            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldDataSnapshot.size
                override fun getNewListSize(): Int = newDataSnapshot.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return itemComparator.areItemsTheSame(
                        oldDataSnapshot[oldItemPosition], newDataSnapshot[newItemPosition]
                    )
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int, newItemPosition: Int
                ): Boolean {
                    return itemComparator.areContentsTheSame(
                        oldDataSnapshot[oldItemPosition], newDataSnapshot[newItemPosition]
                    )
                }
            }

            coroutineScope?.launch {
                try {
                    val result = DiffUtil.calculateDiff(diffCallback)
                    synchronized(dataRows) {
                        dataRows.clear()
                        dataRows.addAll(newDataSnapshot)
                    }
                    result.dispatchUpdatesTo(this@NormalRecyclerAdapter)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // 回退到全量刷新
                    synchronized(dataRows) {
                        dataRows.clear()
                        dataRows.addAll(newDataSnapshot)
                    }
                    notifyDataSetChanged()
                }
            }
        } else {
            val newSize = newRows.size
            synchronized(dataRows) {
                dataRows.clear()
                dataRows.addAll(newRows)
            }

            // 新数据比旧数据少，需要通知删除部分 item ，否则会越界
            if (newSize < oldSize) {
                notifyItemRangeRemoved(newSize, oldSize - newSize)
            }
            notifyItemRangeChanged(0, newSize)
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

    private var itemClickedListener: OnItemClickedListener<T>? = null

    interface OnItemClickedListener<T> {
        fun onItemClicked(position: Int, item: T)
    }

    fun setOnItemClickedListener(listener: OnItemClickedListener<T>?) {
        itemClickedListener = listener
    }

    interface ItemComparator<T> {
        fun areItemsTheSame(oldItem: T, newItem: T): Boolean

        fun areContentsTheSame(oldItem: T, newItem: T): Boolean
    }
}