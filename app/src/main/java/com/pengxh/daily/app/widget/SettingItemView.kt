package com.pengxh.daily.app.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.widget.TextViewCompat
import com.google.android.material.textview.MaterialTextView
import com.pengxh.daily.app.R

/**
 * 设置页通用条目控件：左侧图标（可选）+ 中间标题/副标题 + 右侧控件（图标/箭头/开关/文字/无）
 *
 * XML 用法：
 * ```
 * <com.pengxh.daily.app.widget.SettingItemView
 *     android:id="@+id/floatingItem"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:itemTitle="悬浮权限"
 *     app:itemDesc="核心服务，用于打卡后自动跳回本软件"
 *     app:itemRightType="switchBtn" />
 * ```
 *
 * 开关默认样式取自主题的 switchStyle
 * 单个条目需要自定义开关样式时，挂一个重写了 switchStyle 的 ThemeOverlay：
 * ```
 * <style name="ThemeOverlay.DailyTask.SpecialSwitch" parent="">
 *     <item name="switchStyle">@style/YourSwitchStyle</item>
 * </style>
 *
 * <com.pengxh.daily.app.widget.SettingItemView
 *     android:theme="@style/ThemeOverlay.DailyTask.SpecialSwitch"
 *     app:itemRightType="switchBtn" ... />
 * ```
 */
class SettingItemView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    val iconView: ImageView
    val titleView: MaterialTextView
    val descView: MaterialTextView
    val rightIconView: ImageFilterView
    val switchView: SwitchCompat
    val rightTextView: MaterialTextView

    var title: CharSequence
        get() = titleView.text
        set(value) {
            titleView.text = value
        }

    var desc: CharSequence
        get() = descView.text
        set(value) {
            descView.text = value
            descView.visibility = if (value.isEmpty()) GONE else VISIBLE
        }

    var rightText: CharSequence
        get() = rightTextView.text
        set(value) {
            rightTextView.text = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val paddingHorizontal = resources.getDimensionPixelSize(R.dimen.padding_2x)
        val paddingVertical = resources.getDimensionPixelSize(R.dimen.dp_12)
        setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)

        // 注意：必须用构造传入的 context（携带 android:theme 包装）来 inflate，否则 ThemeOverlay 对 switchStyle 的覆盖不生效
        LayoutInflater.from(context).inflate(R.layout.view_setting_item, this, true)
        iconView = findViewById(R.id.itemIconView)
        titleView = findViewById(R.id.itemTitleView)
        descView = findViewById(R.id.itemDescView)
        rightIconView = findViewById(R.id.itemRightIconView)
        switchView = findViewById(R.id.itemSwitchView)
        rightTextView = findViewById(R.id.itemRightTextView)
        handleAttrs(attrs)
    }

    /**
     * 无 desc 的条目也要和两行条目行高一致：
     * 首次测量时用「标题高度 + 副标题高度 + 上下内边距」算出最小行高，
     * 内容由 gravity=CENTER_VERTICAL 垂直居中，单行条目的标题不再顶在上方。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (rowMinHeight == 0) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            titleView.measure(unspecified, unspecified)
            descView.measure(unspecified, unspecified)
            rowMinHeight =
                paddingTop + paddingBottom + titleView.measuredHeight + descView.measuredHeight
            minimumHeight = rowMinHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun handleAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val ta = context.obtainStyledAttributes(attrs, R.styleable.SettingItemView)
        try {
            val iconRes = ta.getResourceId(R.styleable.SettingItemView_itemIcon, 0)
            if (iconRes != 0) {
                iconView.setImageResource(iconRes)
                iconView.visibility = VISIBLE
            }

            ta.getString(R.styleable.SettingItemView_itemTitle)?.let { titleView.text = it }
            val titleAppearance =
                ta.getResourceId(R.styleable.SettingItemView_itemTitleAppearance, 0)
            if (titleAppearance != 0) {
                TextViewCompat.setTextAppearance(titleView, titleAppearance)
            }
            if (ta.hasValue(R.styleable.SettingItemView_itemTitleColor)) {
                titleView.setTextColor(ta.getColor(R.styleable.SettingItemView_itemTitleColor, 0))
            }

            val descText = ta.getString(R.styleable.SettingItemView_itemDesc)
            if (!descText.isNullOrEmpty()) {
                descView.text = descText
                descView.visibility = VISIBLE
            } else {
                descView.visibility = GONE
            }
            val descAppearance =
                ta.getResourceId(R.styleable.SettingItemView_itemDescAppearance, 0)
            if (descAppearance != 0) {
                TextViewCompat.setTextAppearance(descView, descAppearance)
            }
            if (ta.hasValue(R.styleable.SettingItemView_itemDescColor)) {
                descView.setTextColor(ta.getColor(R.styleable.SettingItemView_itemDescColor, 0))
            }

            if (ta.getBoolean(R.styleable.SettingItemView_itemRightIconRound, false)) {
                rightIconView.roundPercent = 1.0f
            }

            when (ta.getInt(R.styleable.SettingItemView_itemRightType, TYPE_NONE)) {
                TYPE_ICON, TYPE_ARROW -> {
                    val rightIconRes =
                        ta.getResourceId(R.styleable.SettingItemView_itemRightIcon, 0)
                    if (rightIconRes != 0) {
                        rightIconView.setBackgroundResource(rightIconRes)
                    }
                    rightIconView.visibility = VISIBLE
                }

                TYPE_SWITCH -> switchView.visibility = VISIBLE

                TYPE_TEXT -> {
                    ta.getString(R.styleable.SettingItemView_itemRightText)
                        ?.let { rightTextView.text = it }
                    val textAppearance =
                        ta.getResourceId(R.styleable.SettingItemView_itemRightTextAppearance, 0)
                    if (textAppearance != 0) {
                        TextViewCompat.setTextAppearance(rightTextView, textAppearance)
                    }
                    if (ta.hasValue(R.styleable.SettingItemView_itemRightTextColor)) {
                        rightTextView.setTextColor(
                            ta.getColor(R.styleable.SettingItemView_itemRightTextColor, 0)
                        )
                    }
                    rightTextView.visibility = VISIBLE
                }
            }
        } finally {
            ta.recycle()
        }
    }

    // 0 表示尚未计算最小行高
    private var rowMinHeight = 0

    companion object {
        private const val TYPE_NONE = 0
        private const val TYPE_ICON = 1
        private const val TYPE_ARROW = 2
        private const val TYPE_SWITCH = 3
        private const val TYPE_TEXT = 4
    }
}
