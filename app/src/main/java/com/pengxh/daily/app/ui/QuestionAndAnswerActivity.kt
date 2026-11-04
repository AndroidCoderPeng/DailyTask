package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.NormalRecyclerAdapter
import com.pengxh.daily.app.adapter.ViewHolder
import com.pengxh.daily.app.base.KotlinBaseActivity
import com.pengxh.daily.app.databinding.ActivityQuestionAndAnswerBinding
import com.pengxh.daily.app.extensions.readAssetsFile
import com.pengxh.daily.app.model.QuestionAnAnswerModel
import com.pengxh.daily.app.utils.HtmlRenderEngine

class QuestionAndAnswerActivity : KotlinBaseActivity<ActivityQuestionAndAnswerBinding>() {
    private val context = this@QuestionAndAnswerActivity
    private val gson by lazy { Gson() }

    override fun initEvent() {

    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        binding.marqueeView.requestFocus()

        val assetsFile = readAssetsFile("QuestionAndAnswer.json")
        val dataRows = gson.fromJson<MutableList<QuestionAnAnswerModel>>(
            assetsFile, object : TypeToken<MutableList<QuestionAnAnswerModel>>() {}.type
        )
        binding.recyclerView.adapter = object :
            NormalRecyclerAdapter<QuestionAnAnswerModel>(R.layout.item_q_a_rv_l, dataRows) {
            override fun convertView(
                viewHolder: ViewHolder, position: Int, item: QuestionAnAnswerModel
            ) {
                viewHolder.setText(R.id.questionView, "${position + 1}、${item.question}")
                val textView = viewHolder.getView<TextView>(R.id.answerView)
                HtmlRenderEngine.Builder()
                    .setContext(context)
                    .setHtmlContent(item.answer)
                    .setTargetView(textView)
                    .setOnGetImageSourceListener(object :
                        HtmlRenderEngine.OnGetImageSourceListener {
                        override fun imageSource(url: String) {

                        }
                    }).build().load()
            }
        }
    }

    override fun initViewBinding(): ActivityQuestionAndAnswerBinding {
        return ActivityQuestionAndAnswerBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, systemBars.bottom)
            insets
        }

        WindowInsetsControllerCompat(window, binding.rootView).isAppearanceLightStatusBars = true
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}