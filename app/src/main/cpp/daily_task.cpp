//
// Created by pengx on 2026/3/4.
//

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cmath>
#include <sstream>
#include <iomanip>
#include <cstring>

#include "watermark.hpp"

#define TAG "JNI-DailyTask"
#define LGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void drawPixel(uint32_t *pixels, int width, int height, int x, int y, uint32_t color) {
    if (x < 0 || x >= width || y < 0 || y >= height) return;
    pixels[y * width + x] = color;
}

static void drawTextToBitmap(uint32_t *pixels, int width, int height,
                             const char *text, float posX, float posY,
                             float textSize, int alpha) {
    // 简化的文字绘制 - 使用矩形模拟文字
    int textLen = strlen(text);
    int charWidth = static_cast<int>(textSize * 0.6f);  // 每个字符宽度
    int charHeight = static_cast<int>(textSize);         // 字符高度
    int totalWidth = textLen * charWidth;

    // 计算起始位置（居中）
    int startX = static_cast<int>(posX - totalWidth / 2);
    int startY = static_cast<int>(posY - charHeight / 2);

    // 半透明黑色
    uint32_t color = (alpha << 24) | 0x000000;

    // 绘制文字背景块（模拟文字）
    for (int row = 0; row < charHeight; row++) {
        for (int col = 0; col < totalWidth; col++) {
            int px = startX + col;
            int py = startY + row;

            // 简单的斜线纹理模拟文字
            if ((col + row) % 3 == 0) {
                drawPixel(pixels, width, height, px, py, color);
            }
        }
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_pengxh_daily_app_utils_DailyTask_getWatermarkConfig(JNIEnv *env, jobject clazz) {
    auto *config = new WatermarkConfig();
    Watermark watermark;
    *config = watermark.getWatermarkConfig();
    return reinterpret_cast<jlong>(config);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pengxh_daily_app_utils_DailyTask_releaseWatermarkConfig(JNIEnv *env, jobject clazz,
                                                                 jlong configPtr) {
    auto *config = reinterpret_cast<WatermarkConfig *>(configPtr);
    delete config;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pengxh_daily_app_utils_DailyTask_drawWatermark(JNIEnv *env, jobject thiz,
                                                        jobject bitmap,
                                                        jint width, jint height,
                                                        jlong ptr) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LGE("Failed to get bitmap info");
        return;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LGE("Bitmap format not supported");
        return;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LGE("Failed to lock bitmap pixels");
        return;
    }

    auto *config = reinterpret_cast<WatermarkConfig *>(ptr);
    Watermark watermark;

    // 计算文字宽高（简化）
    float textWidth = strlen(config->text) * config->textSize * 0.6f;
    float textHeight = config->textSize;

    // 计算所有位置
    std::vector<WatermarkPosition> positions = watermark.calculatePositions(
            static_cast<int16_t>(width),
            static_cast<int16_t>(height),
            textWidth,
            textHeight,
            *config);

    // 清空Bitmap为透明
    auto *pixelPtr = static_cast<uint32_t *>(pixels);
    memset(pixels, 0, info.stride * info.height);

    // 在每个位置绘制水印
    for (const auto &pos: positions) {
        drawTextToBitmap(pixelPtr, width, height,
                         config->text,
                         pos.x, pos.y,
                         config->textSize,
                         config->alpha);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    LGD("Watermark drawn with %zu positions", positions.size());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pengxh_daily_app_utils_DailyTask_getWatermarkText(JNIEnv *env, jobject thiz) {
    Watermark watermark;
    std::string text = watermark.getWatermarkText();
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pengxh_daily_app_utils_DailyTask_applicationCopyright(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF("版权所有 © 2026 AndroidCoderPeng, All Rights Reserved.");
}