package com.pengxh.kt.lite.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.pengxh.kt.lite.R
import com.pengxh.kt.lite.extensions.getScreenWidth


/**
 * 方向盘控件
 * */
@SuppressLint("ClickableViewAccessibility")
class SteeringWheelView(context: Context, attrs: AttributeSet) :
    RelativeLayout(context, attrs) {

    init {
        val type = context.obtainStyledAttributes(attrs, R.styleable.SteeringWheelView)
        var diameter = type.getDimension(R.styleable.SteeringWheelView_ctrl_diameter, 250f)
        type.recycle()
        if (diameter <= 150f) {
            diameter = 150f
        }

        if (diameter >= context.getScreenWidth()) {
            diameter = context.getScreenWidth().toFloat()
        }

        val layoutParams = LayoutParams(diameter.toInt(), diameter.toInt())
        val view = LayoutInflater.from(context).inflate(R.layout.widget_view_steering_wheel, this)
        val rootView = view.findViewById<ConstraintLayout>(R.id.rootView)
        rootView.layoutParams = layoutParams

        val leftButton = view.findViewById<ImageButton>(R.id.leftButton)
        leftButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    listener?.onLeftTurn()
                }

                MotionEvent.ACTION_UP -> {
                    listener?.onActionTurnUp(Direction.LEFT)
                }
            }
            postInvalidate()
            false
        }

        view.findViewById<ImageButton>(R.id.topButton).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    listener?.onTopTurn()
                }

                MotionEvent.ACTION_UP -> {
                    listener?.onActionTurnUp(Direction.TOP)
                }
            }
            postInvalidate()
            false
        }
        view.findViewById<ImageButton>(R.id.rightButton).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    listener?.onRightTurn()
                }

                MotionEvent.ACTION_UP -> {
                    listener?.onActionTurnUp(Direction.RIGHT)
                }
            }
            postInvalidate()
            false
        }
        view.findViewById<ImageButton>(R.id.bottomButton).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    listener?.onBottomTurn()
                }

                MotionEvent.ACTION_UP -> {
                    listener?.onActionTurnUp(Direction.BOTTOM)
                }
            }
            postInvalidate()
            false
        }
        view.findViewById<ImageButton>(R.id.centerButton).setOnClickListener {
            listener?.onCenterClicked()
        }
    }

    enum class Direction {
        LEFT, TOP, RIGHT, BOTTOM
    }

    private var listener: OnWheelTouchListener? = null

    fun setOnWheelTouchListener(listener: OnWheelTouchListener?) {
        this.listener = listener
    }

    interface OnWheelTouchListener {
        /**
         * 中间
         */
        fun onCenterClicked()

        /**
         * 左
         */
        fun onLeftTurn()

        /**
         * 上
         */
        fun onTopTurn()

        /**
         * 右
         */
        fun onRightTurn()

        /**
         * 下
         */
        fun onBottomTurn()

        /**
         * 松开
         */
        fun onActionTurnUp(dir: Direction)
    }
}