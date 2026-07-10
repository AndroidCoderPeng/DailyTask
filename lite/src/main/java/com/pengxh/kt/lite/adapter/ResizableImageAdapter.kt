package com.pengxh.kt.lite.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.pengxh.kt.lite.R


/**
 * 可调整数量的图片适配器，做多9张图片，每行3张
 *
 * @param context 使用适配的上下文
 * @param images RecyclerView内数据
 * @param viewWidth RecyclerView实际宽度，一般情况下就是屏幕宽度，但是如果有其他控件和它在同一行，需要计算实际宽度，不然无法正确显示RecyclerView item的布局
 * */
class ResizableImageAdapter(
    private val images: MutableList<String>,
    private val viewWidth: Int
) : RecyclerView.Adapter<ViewHolder>() {

    private val limit = 9
    private val spanCount = 3
    private var showAddButton: Boolean = images.size < limit

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_editable_rv_g, parent, false
        )
        val imageSize = viewWidth / spanCount
        val params = LinearLayout.LayoutParams(imageSize, imageSize)
        view.findViewById<ImageView>(R.id.imageView).layoutParams = params
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val imageView = holder.getView<ImageView>(R.id.imageView)
        if (position == itemCount - 1 && images.size < limit) {
            imageView.setImageResource(R.drawable.ic_add_pic)
            imageView.setOnClickListener { //添加图片
                itemClickListener?.onAddImageClick()
            }
        } else {
            Glide.with(holder.itemView.context).load(images[position]).into(imageView)
            imageView.setOnClickListener { // 点击操作，查看大图
                itemClickListener?.onItemClick(holder.bindingAdapterPosition)
            }
            // 长按监听
            imageView.setOnLongClickListener { v -> //长按删除
                itemClickListener?.onItemLongClick(v, holder.bindingAdapterPosition)
                true
            }
        }
    }

    override fun getItemCount(): Int = images.size + if (showAddButton) 1 else 0

    private var itemClickListener: OnItemClickListener? = null

    fun setOnItemClickListener(itemClickListener: OnItemClickListener?) {
        this.itemClickListener = itemClickListener
    }

    interface OnItemClickListener {
        fun onAddImageClick()

        fun onItemClick(position: Int)

        fun onItemLongClick(view: View?, position: Int)
    }

    fun addItem(imagePath: String) {
        if (images.size >= limit) return
        images.add(imagePath)
        val insertedPosition = images.size - 1  // 新图片的位置
        if (images.size == limit) {
            // 加到第9张：加号按钮消失，先通知 removed，再通知图片 inserted
            showAddButton = false
            notifyItemRemoved(insertedPosition)   // 加号按钮消失
            notifyItemInserted(insertedPosition)  // 第9张图片出现（同一位置，RecyclerView 会正确处理）
        } else {
            // 普通插入：加号按钮往后移动一格
            notifyItemInserted(insertedPosition)
        }
    }

    fun removeItem(position: Int) {
        if (position < 0 || position >= images.size) return
        val wasAtLimit = images.size == limit
        images.removeAt(position)
        if (wasAtLimit) {
            // 从9张删到8张：加号按钮重新出现
            showAddButton = true
            notifyItemRemoved(position)
            notifyItemInserted(images.size)  // 加号按钮在末尾出现
        } else {
            notifyItemRemoved(position)
        }
    }

    fun clear() {
        val oldCount = itemCount
        images.clear()
        showAddButton = true  // 清空后加号按钮重新显示
        notifyItemRangeRemoved(0, oldCount)
    }


    fun getImages(): List<String> {
        return images
    }
}