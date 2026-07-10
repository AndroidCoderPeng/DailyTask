package com.pengxh.kt.lite.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class KotlinBaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private var _binding: VB? = null

    protected val binding: VB
        get() = _binding ?: throw IllegalStateException(
            "Binding is only valid between onCreate and onDestroy"
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = initViewBinding()
            setContentView(binding.root)
            setupTopBarLayout()
            observeRequestState()
            initOnCreate(savedInstanceState)
            initEvent()
        } catch (e: Exception) {
            // 初始化失败，安全退出
            finish()
            throw e
        }
    }

    /**
     * 初始化ViewBinding
     */
    abstract fun initViewBinding(): VB

    /**
     * 特定页面定制沉浸式状态栏
     */
    abstract fun setupTopBarLayout()

    /**
     * 初始化默认数据
     */
    abstract fun initOnCreate(savedInstanceState: Bundle?)

    /**
     * 数据请求状态监听
     */
    abstract fun observeRequestState()

    /**
     * 初始化业务逻辑
     */
    abstract fun initEvent()

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}