//
// Created by pengx on 2026/3/4.
//

#include "watermark.hpp"
#include <android/log.h>
#include <cmath>
#include <sstream>
#include <iomanip>

#define TAG "JNI-Watermark"
#define LGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static const char ENCRYPTION_KEY[] = {0x5A, 0x3F, 0x7B, 0x2D, 0x1E, 0x9C, 0x4A, 0x8F};

static const unsigned char OBFUSCATED_TEXT[] = {
        0x3F, 0x3A, 0x15, 0x1B,
        0x0A, 0x36, 0x16, 0x2D,
        0x25, 0x00
};

std::string Watermark::obfuscateString(const char *input) {
    std::string result;
    size_t keyLen = sizeof(ENCRYPTION_KEY);
    size_t i = 0;
    while (input[i] != '\0') {
        result += input[i] ^ ENCRYPTION_KEY[i % keyLen];
        i++;
    }
    return result;
}

std::string Watermark::getWatermarkText() {
    char decrypted[64] = {0};
    size_t i = 0;
    size_t keyLen = sizeof(ENCRYPTION_KEY);

    while (OBFUSCATED_TEXT[i] != 0x00 && i < sizeof(decrypted) - 1) {
        decrypted[i] = OBFUSCATED_TEXT[i] ^ ENCRYPTION_KEY[i % keyLen];
        i++;
    }
    decrypted[i] = '\0';
    return decrypted;
}

WatermarkConfig Watermark::getWatermarkConfig() {
    WatermarkConfig config{};

    memset(config.text, 0, sizeof(config.text));
    std::string text = getWatermarkText();
    strncpy(config.text, text.c_str(), sizeof(config.text) - 1);

    config.textSize = 40.0f;        // 文字大小
    config.rotation = -30.0f;       // 旋转角度
    config.lineSpacing = 150.0f;    // 行间距
    config.columnSpacing = 200.0f;  // 列间距
    config.textColor = 0x000000;    // 黑色
    config.alpha = 25;              // 透明度 (0-255)

    return config;
}

std::vector<WatermarkPosition> Watermark::calculatePositions(int16_t canvasWidth,
                                                             int16_t canvasHeight,
                                                             float textWidth,
                                                             float textHeight,
                                                             const WatermarkConfig &config) {
    std::vector<WatermarkPosition> positions;

    float radian = config.rotation * M_PI / 180.0f;
    float absCos = fabs(cos(radian));
    float absSin = fabs(sin(radian));

    float rotatedWidth = textWidth * absCos + textHeight * absSin;
    float rotatedHeight = textWidth * absSin + textHeight * absCos;

    int cols = static_cast<int>(ceil(canvasWidth / (rotatedWidth + config.columnSpacing))) + 2;
    int rows = static_cast<int>(ceil(canvasHeight / (rotatedHeight + config.lineSpacing))) + 2;

    float startX = -rotatedWidth;
    float startY = -rotatedHeight;

    for (int row = 0; row < rows; row++) {
        for (int col = 0; col < cols; col++) {
            WatermarkPosition pos{};
            pos.x = startX + col * (rotatedWidth + config.columnSpacing) + rotatedWidth / 2;
            pos.y = startY + row * (rotatedHeight + config.lineSpacing) + rotatedHeight / 2;
            pos.rotation = config.rotation;
            positions.push_back(pos);
        }
    }

    LGD("Calculated %zu watermark positions for %dx%d canvas", positions.size(), canvasWidth,
        canvasHeight);
    return positions;
}