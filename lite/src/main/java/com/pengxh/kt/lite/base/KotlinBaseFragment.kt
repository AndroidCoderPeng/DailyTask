package com.pengxh.kt.lite.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

abstract class KotlinBaseFragment<VB : ViewBinding> : Fragment() {

    private var _binding: VB? = null

    protected val binding: VB
        get() = _binding ?: throw IllegalStateException(
            "Binding is only valid between onCreateView and onDestroyView"
        )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        _binding = try {
            initViewBinding(inflater, container)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize ViewBinding", e)
        }
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initOnCreate(savedInstanceState)
        setupTopBarLayout()
        observeRequestState()
        initEvent()
    }

    abstract fun initViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    /**
     * 沉浸式状态栏
     */
    abstract fun setupTopBarLayout()

    /**
     * 初始化布局以及控件
     */
    abstract fun initOnCreate(savedInstanceState: Bundle?)

    /**
     * 网络请求状态监听
     */
    abstract fun observeRequestState()

    /**
     * 业务逻辑，按钮等事件
     */
    abstract fun initEvent()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}