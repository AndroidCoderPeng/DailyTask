package com.pengxh.daily.app.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.WidgetEmptyViewBinding

/**
 * 通用空态控件：图标 + 主文案 + 副文案 +（可选）「重新加载」按钮
 *
 * XML 用法：
 * ```
 * <com.pengxh.daily.app.widget.EmptyView
 *     android:id="@+id/emptyView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:visibility="gone"
 *     app:emptyText="暂无任务"
 *     app:emptyDesc="添加一个任务开始吧"
 *     app:show_reload_button="true" />
 * ```
 *
 * 代码用法：
 * ```
 * binding.emptyView.setOnReloadClickListener { loadData() }
 * binding.emptyView.setEmptyState(isEmpty)
 * ```
 *
 * 注意：监听器刻意不叫 setOnClickListener，
 * 避免和 View.setOnClickListener(View.OnClickListener) 重载混淆（自定义接口不是 fun interface，
 * 写 lambda 时不会做 SAM 转换，会静默命中 View 的重载，回调永远不触发）。
 */
class EmptyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = WidgetEmptyViewBinding.inflate(LayoutInflater.from(context), this)
    
    val iconView: ShapeableImageView get() = binding.emptyIconView
    val titleView: MaterialTextView get() = binding.emptyTitleView
    val descView: MaterialTextView get() = binding.emptyDescView
    val reloadButton: MaterialButton get() = binding.reloadButton

    /**
     * 主文案，为空则隐藏
     * */
    var title: CharSequence?
        get() = titleView.text
        set(value) {
            titleView.text = value
            titleView.isVisible = !value.isNullOrEmpty()
        }

    /**
     * 副文案，为空则隐藏
     * */
    var desc: CharSequence?
        get() = descView.text
        set(value) {
            descView.text = value
            descView.isVisible = !value.isNullOrEmpty()
        }

    /**
     * 是否展示「重新加载」按钮
     * */
    var showReloadButton: Boolean = false
        set(value) {
            field = value
            reloadButton.isVisible = value
        }

    private var reloadListener: OnReloadClickListener? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        reloadButton.setOnClickListener { reloadListener?.onReloadClick() }
        handleAttrs(attrs)
    }

    fun setOnReloadClickListener(listener: OnReloadClickListener?) {
        reloadListener = listener
    }

    /**
     * 空态开关：true 显示空态，false 隐藏
     * */
    fun setEmptyState(isEmpty: Boolean) {
        isVisible = isEmpty
    }

    /**
     * 运行期更新文案并展示空态（传 null 表示不改）
     * */
    fun show(title: CharSequence? = null, desc: CharSequence? = null) {
        if (title != null) this.title = title
        if (desc != null) this.desc = desc
        setEmptyState(true)
    }

    private fun handleAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        // 用 theme 取属性，ThemeOverlay 覆盖才能生效（与 SettingItemView 保持一致）
        val ta = context.obtainStyledAttributes(attrs, R.styleable.EmptyView)
        try {
            val iconRes = ta.getResourceId(R.styleable.EmptyView_emptyIcon, 0)
            if (iconRes != 0) iconView.setImageResource(iconRes)

            title = ta.getString(R.styleable.EmptyView_emptyText)
            desc = ta.getString(R.styleable.EmptyView_emptyDesc)

            val reloadText = ta.getString(R.styleable.EmptyView_reloadText)
            if (!reloadText.isNullOrEmpty()) {
                reloadButton.text = reloadText
            }

            showReloadButton = ta.getBoolean(R.styleable.EmptyView_show_reload_button, true)
        } finally {
            ta.recycle()
        }
    }


    interface OnReloadClickListener {
        fun onReloadClick()
    }
}