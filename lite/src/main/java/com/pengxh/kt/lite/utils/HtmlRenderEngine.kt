package com.pengxh.kt.lite.utils

import android.content.Context
import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.bumptech.glide.Glide
import com.pengxh.kt.lite.R
import com.pengxh.kt.lite.extensions.getScreenWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xml.sax.XMLReader
import java.util.Locale

class HtmlRenderEngine(builder: Builder) {

    class Builder {
        lateinit var context: Context
        lateinit var html: String
        lateinit var textView: TextView
        lateinit var imageSourceListener: OnGetImageSourceListener

        /**
         * 设置上下文
         * */
        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        /**
         * 设置html格式的文本
         * */
        fun setHtmlContent(html: String): Builder {
            this.html = html
            return this
        }

        /**
         * 设置显示html格式文本的View
         * */
        fun setTargetView(textView: TextView): Builder {
            this.textView = textView
            return this
        }

        /**
         * 设置html里面图片地址回调监听
         * */
        fun setOnGetImageSourceListener(imageSourceListener: OnGetImageSourceListener): Builder {
            this.imageSourceListener = imageSourceListener
            return this
        }

        fun build(): HtmlRenderEngine {
            if (!::context.isInitialized || !::html.isInitialized || !::textView.isInitialized || !::imageSourceListener.isInitialized) {
                throw IllegalStateException("All properties must be initialized before building.")
            }
            return HtmlRenderEngine(this)
        }
    }

    private val context = builder.context
    private val html = builder.html
    private val textView = builder.textView
    private val listener = builder.imageSourceListener

    fun load() {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)

        scope.launch(Dispatchers.Main) {
            textView.movementMethod = LinkMovementMethod.getInstance()
            // 先设置纯文本内容
            textView.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)

            withContext(Dispatchers.IO) {
                val imageGetter = Html.ImageGetter { source ->
                    val drawable = try {
                        Glide.with(context.applicationContext).load(source).submit().get()
                    } catch (_: Exception) {
                        ResourcesCompat.getDrawable(
                            context.resources, R.mipmap.load_image_error, context.theme
                        )
                    }

                    if (drawable != null) {
                        var width = drawable.intrinsicWidth
                        var height = drawable.intrinsicHeight

                        // 确保textView的宽度大于0再进行缩放计算
                        val viewWidth = if (textView.width > 0)
                            textView.width
                        else
                            context.getScreenWidth()

                        //对图片按比例缩放尺寸
                        if (width > 0) {
                            val scale = viewWidth / width.toFloat()
                            width = (scale * width).toInt()
                            height = (scale * height).toInt()
                        }
                        drawable.setBounds(0, 0, width, height)
                    }

                    //return
                    drawable
                }

                val htmlText = Html.fromHtml(
                    html, Html.FROM_HTML_MODE_LEGACY, imageGetter, object : Html.TagHandler {
                        override fun handleTag(
                            opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader
                        ) {
                            if (tag.lowercase(Locale.getDefault()) == "img") {
                                val len = output.length
                                val images = output.getSpans(
                                    len - 1, len, ImageSpan::class.java
                                )

                                if (images.isNotEmpty()) {
                                    val imgSource = images[0].source ?: return
                                    output.setSpan(object : ClickableSpan() {
                                        override fun onClick(widget: View) {
                                            listener.imageSource(imgSource)
                                        }
                                    }, len - 1, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    textView.text = htmlText
                }
            }
        }
    }

    interface OnGetImageSourceListener {
        fun imageSource(url: String)
    }
}