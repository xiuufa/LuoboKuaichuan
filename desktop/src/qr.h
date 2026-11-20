// 萝卜快传 · 二维码 SVG 生成（与安卓端 QrSvg 输出风格一致）
#pragma once

#include <string>

namespace luobo {

std::string qrSvg(const std::string& data, const std::string& foreground = "#201B17",
                  const std::string& background = "#FFFFFF", int outputSize = 480, int margin = 1);

}  // namespace luobo
