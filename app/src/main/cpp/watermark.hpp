//
// Created by pengx on 2026/3/4.
//

#ifndef DAILYTASK_WATERMARK_HPP
#define DAILYTASK_WATERMARK_HPP

#include <jni.h>
#include <string>
#include <vector>

// 水印配置结构体
struct WatermarkConfig {
    char text[64];
    float textSize;
    float rotation;
    float lineSpacing;
    float columnSpacing;
    int textColor;
    int alpha;
};

// 水印位置结构体
struct WatermarkPosition {
    float x;
    float y;
    float rotation;
};

class Watermark {
public:
    std::string getWatermarkText();

    WatermarkConfig getWatermarkConfig();

    std::vector<WatermarkPosition> calculatePositions(int16_t canvasWidth,
                                                      int16_t canvasHeight,
                                                      float textWidth,
                                                      float textHeight,
                                                      const WatermarkConfig &config);

    std::string obfuscateString(const char *input);
};


#endif //DAILYTASK_WATERMARK_HPP
