package com.pengxh.daily.app.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.ScaleAnimation
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.forEachIndexed
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.viewpager.widget.ViewPager
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.BaseFragmentAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.extensions.initImmersionBar
import com.pengxh.daily.app.fragment.DailyTaskFragment
import com.pengxh.daily.app.fragment.SettingsFragment
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.setScreenBrightness
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog
import java.util.Random
import kotlin.math.abs

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val kTag = "MainActivity"
    private val fragmentPages = mutableListOf<Fragment>().apply {
        add(DailyTaskFragment())
        add(SettingsFragment())
    }
    private val clockAnimationHandler = Handler(Looper.getMainLooper())
    private var menuItem: MenuItem? = null
    private lateinit var insetsController: WindowInsetsControllerCompat
    private val broadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Constant.BROADCAST_SHOW_MASK_VIEW_ACTION) {
                    showMaskView()
                }
            }
        }
    }
    private lateinit var gestureDetector: GestureDetector

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        insetsController = WindowCompat.getInsetsController(window, binding.rootView)
        binding.rootView.initImmersionBar(this, true, R.color.back_ground_color)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun initOnCreate(savedInstanceState: Bundle?) {
        val filter = IntentFilter().apply {
            addAction(Constant.BROADCAST_SHOW_MASK_VIEW_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }

        Intent(this, ForegroundRunningService::class.java).apply {
            startService(this)
        }
        val fragmentAdapter = BaseFragmentAdapter(supportFragmentManager, fragmentPages)
        binding.viewPager.adapter = fragmentAdapter
        val isFirst = SaveKeyValues.getValue("isFirst", true) as Boolean
        if (isFirst) {
            AlertMessageDialog.Builder()
                .setContext(this)
                .setTitle("温馨提醒")
                .setMessage("本软件仅供内部使用，严禁商用或者用作其他非法用途")
                .setPositiveButton("知道了")
                .setOnDialogButtonClickListener(object :
                    AlertMessageDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick() {
                        SaveKeyValues.putValue("isFirst", false)
                    }
                }).build().show()
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val isGestureDetector =
                    SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean
                if (isGestureDetector) {
                    val deltaY = abs(e2.y - (e1?.y ?: e2.y))
                    Log.d(kTag, "onFling: $deltaY")

                    // 从上向下滑动手势
                    if (deltaY > 1000 && (e2.y - (e1?.y ?: e2.y)) > 0) {
                        showMaskView()
                        return true
                    }

                    // 从下向上滑动手势
                    if (deltaY > 1000 && (e2.y - (e1?.y ?: e2.y)) < 0) {
                        hideMaskView()
                        return true
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureDetector.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun initEvent() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val itemId = item.itemId
            val menuItems = binding.bottomNavigation.menu
            menuItems.forEachIndexed { index, _ ->
                if (menuItems[index].itemId == itemId) {
                    binding.viewPager.currentItem = index
                    return@forEachIndexed
                }
            }
            false
        }

        binding.floatingActionButton.setOnClickListener {
            DailyTaskApplication.get().sharedViewModel.addTaskCode.value = 1
        }

        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int, positionOffset: Float, positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                if (position < 0 || position >= binding.bottomNavigation.menu.size) {
                    return
                }

                // 获取当前选中的菜单项并取消选中
                val currentMenuItem = menuItem ?: binding.bottomNavigation.menu[0]
                currentMenuItem.isChecked = false

                // 更新新的选中菜单项
                when (position) {
                    0 -> menuItem = binding.bottomNavigation.menu[position]
                    1 -> menuItem = binding.bottomNavigation.menu[position + 1]
                }
                menuItem?.isChecked = true
            }

            override fun onPageScrollStateChanged(state: Int) {}
        })
    }

    override fun observeRequestState() {

    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (binding.maskView.isVisible) {
                hideMaskView()
            } else {
                showMaskView()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var clockAnimationRunnable = object : Runnable {
        override fun run() {
            // 确保视图已经布局完成
            if (binding.maskView.width == 0 || binding.maskView.height == 0) return

            // 获取时钟控件尺寸
            binding.clockView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val clockWidth = binding.clockView.measuredWidth
            val clockHeight = binding.clockView.measuredHeight

            // 计算可移动范围
            val maxX = binding.maskView.width - clockWidth
            val maxY = binding.maskView.height - clockHeight

            // 确保范围有效
            if (maxX <= 0 || maxY <= 0) return

            // 生成随机位置
            val random = Random()
            val newX = random.nextInt(maxX.coerceAtLeast(1))
            val newY = random.nextInt(maxY.coerceAtLeast(1))

            // 应用动画移动到新位置
            binding.clockView.animate()
                .x(newX.toFloat())
                .y(newY.toFloat())
                .setDuration(1000)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            // 每30秒执行一次位置变换
            clockAnimationHandler.postDelayed(this, 30000)
        }
    }

    /**
     * 显示蒙层以及其它组件
     * */
    private fun showMaskView() {
        //隐藏状态栏显示
        insetsController.hide(WindowInsetsCompat.Type.statusBars())

        //显示蒙层
        binding.maskView.visibility = View.VISIBLE
        val visibleAction = ScaleAnimation(1.0f, 1.0f, 0.0f, 1.0f)
        visibleAction.duration = 500
        binding.maskView.startAnimation(visibleAction)
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)

        //隐藏任务界面
        binding.rootView.visibility = View.GONE

        //隐藏悬浮窗显示
        sendBroadcast(Intent(Constant.BROADCAST_HIDE_FLOATING_WINDOW_ACTION))

        //启动时钟位置变换动画
        clockAnimationHandler.postDelayed(clockAnimationRunnable, 30000)
    }

    /**
     * 隐藏蒙层以及其它组件
     * */
    private fun hideMaskView() {
        //停止时钟动画
        clockAnimationHandler.removeCallbacks(clockAnimationRunnable)

        //恢复状态栏显示
        insetsController.show(WindowInsetsCompat.Type.statusBars())

        //隐藏蒙层
        binding.maskView.visibility = View.GONE
        val invisibleAction = ScaleAnimation(1.0f, 1.0f, 1.0f, 0.0f)
        invisibleAction.duration = 500
        binding.maskView.startAnimation(invisibleAction)
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        //显示任务界面
        binding.rootView.visibility = View.VISIBLE

        //恢复悬浮窗显示
        sendBroadcast(Intent(Constant.BROADCAST_SHOW_FLOATING_WINDOW_ACTION))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
    }
}