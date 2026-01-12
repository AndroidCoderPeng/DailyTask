package com.pengxh.daily.app.ui

import android.os.Bundle
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityQuestionAndAnswerBinding
import com.pengxh.daily.app.model.QuestionAnAnswerModel
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.readAssetsFile
import com.pengxh.kt.lite.utils.HtmlRenderEngine

class QuestionAndAnswerActivity : KotlinBaseActivity<ActivityQuestionAndAnswerBinding>() {
    private val context = this
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
                viewHolder.setText(R.id.questionView, item.question)
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
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}