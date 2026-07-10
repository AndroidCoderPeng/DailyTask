package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.annotation.ColorInt
import com.pengxh.kt.lite.R
import com.pengxh.kt.lite.databinding.BottomActionSheetBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.resetParams

/**
 * 底部列表Sheet
 */
class BottomActionSheet private constructor(builder: Builder) : Dialog(builder.context) {
    private val ctx: Context = builder.context
    private val sheetItems: List<String> = builder.actionItems
    private val itemTextColor: Int = builder.color
    private val sheetListener: OnActionSheetListener = builder.listener

    class Builder {
        lateinit var context: Context
        lateinit var actionItems: List<String>
        var color = R.color.blue
        lateinit var listener: OnActionSheetListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setActionItemTitle(items: List<String>): Builder {
            actionItems = items
            return this
        }

        fun setItemTextColor(@ColorInt color: Int): Builder {
            this.color = color
            return this
        }

        fun setOnActionSheetListener(listener: OnActionSheetListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): BottomActionSheet {
            return BottomActionSheet(this)
        }
    }

    private val binding: BottomActionSheetBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.resetParams(Gravity.BOTTOM, R.style.ActionSheetDialogAnimation, 1f)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        binding.itemListView.adapter = ItemListAdapter(ctx)
        binding.itemListView.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                dismiss()
                sheetListener.onActionItemClick(position)
            }
        binding.sheetCancelView.setTextColor(itemTextColor)
        binding.sheetCancelView.setOnClickListener { dismiss() }
    }

    interface OnActionSheetListener {
        fun onActionItemClick(position: Int)
    }

    inner class ItemListAdapter(context: Context) : BaseAdapter() {

        private val inflater: LayoutInflater = LayoutInflater.from(context)

        override fun getCount(): Int = sheetItems.size

        override fun getItem(position: Int): Any = sheetItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, viewGroup: ViewGroup?): View {
            val view: View
            val holder: ItemViewHolder
            if (convertView == null) {
                holder = ItemViewHolder()
                view = inflater.inflate(R.layout.item_action_sheet, null)
                holder.sheetItemView = view.findViewById(R.id.sheetItemView)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ItemViewHolder
            }
            when (position) {
                0 -> {
                    holder.sheetItemView.setBackgroundResource(R.drawable.sheet_item_top_selector)
                }

                sheetItems.size - 1 -> {
                    holder.sheetItemView.setBackgroundResource(R.drawable.sheet_item_bottom_selector)
                }

                else -> {
                    holder.sheetItemView.setBackgroundResource(R.drawable.sheet_item_middle_selector)
                }
            }
            holder.sheetItemView.text = sheetItems[position]
            holder.sheetItemView.textSize = 16f
            holder.sheetItemView.setTextColor(itemTextColor)
            //需要动态设置item的高度
            val param = AbsListView.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 44.dp2px(ctx)
            )
            view.layoutParams = param
            return view
        }

    }

    internal class ItemViewHolder {
        lateinit var sheetItemView: TextView
    }
}