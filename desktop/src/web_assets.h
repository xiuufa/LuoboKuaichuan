// 由 tools/gen_assets.py 生成，把网页资源内嵌进二进制
#pragma once

#include <string>
#include <unordered_map>

namespace luobo {
namespace web_assets {

const std::unordered_map<std::string, std::string>& files();

}  // namespace web_assets
}  // namespace luobo
