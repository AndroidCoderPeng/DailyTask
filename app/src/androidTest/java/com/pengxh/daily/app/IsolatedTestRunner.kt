package com.pengxh.daily.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context
    ): Application = super.newApplication(classLoader, Application::class.java.name, context)
}
