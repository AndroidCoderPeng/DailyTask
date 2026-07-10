package com.pengxh.kt.lite.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.pengxh.kt.lite.R
import com.pengxh.kt.lite.extensions.getScreenWidth

class EasyPopupWindow(context: Context) : PopupWindow() {

    init {
        width = ((context.getScreenWidth() * 0.4).toInt())
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isOutsideTouchable = true
        isFocusable = true
        animationStyle = R.style.EasyPopupAnimation
        setBackgroundDrawable(null)
        contentView = LayoutInflater.from(context).inflate(
            R.layout.popup_menu_option, null, false
        )
    }

    fun set(menuItems: List<MenuItem>, windowClickListener: OnPopupWindowClickListener) {
        if (menuItems.isEmpty()) {
            return
        }

        val listView = contentView.findViewById<ListView>(R.id.listView)
        val inflater = LayoutInflater.from(contentView.context)
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int {
                return menuItems.size
            }

            override fun getItem(position: Int): Any {
                return menuItems[position]
            }

            override fun getItemId(position: Int): Long {
                return position.toLong()
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view: View
                val holder: PopupWindowHolder
                if (convertView == null) {
                    view = inflater.inflate(R.layout.item_popup_menu, null)
                    holder = PopupWindowHolder()
                    holder.imageView = view.findViewById(R.id.imageView)
                    holder.titleView = view.findViewById(R.id.titleView)
                    view.tag = holder
                } else {
                    view = convertView
                    holder = view.tag as PopupWindowHolder
                }

                // 添加安全检查防止资源错误
                if (position >= 0 && position < menuItems.size) {
                    holder.imageView.setBackgroundResource(menuItems[position].icon)
                    holder.titleView.text = menuItems[position].name
                }
                return view
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position >= 0 && position < menuItems.size) {
                windowClickListener.onPopupItemClicked(position)
            }
            dismiss()
        }
    }

    interface OnPopupWindowClickListener {
        fun onPopupItemClicked(position: Int)
    }

    private class PopupWindowHolder {
        lateinit var imageView: ImageView
        lateinit var titleView: TextView
    }

    /**
     * 菜单数据模型
     * */
    data class MenuItem(val icon: Int, val name: String)
}