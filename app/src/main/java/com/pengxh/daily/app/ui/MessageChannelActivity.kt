package com.pengxh.daily.app.ui

import android.os.Bundle
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.kt.lite.base.KotlinBaseActivity

class MessageChannelActivity: KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val kTag = "MessageChannelActivity"

    override fun initViewBinding(): ActivityMessageChannelBinding {
        return ActivityMessageChannelBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        
    }

    override fun observeRequestState() {
        
    }

    override fun initEvent() {
        
    }
}