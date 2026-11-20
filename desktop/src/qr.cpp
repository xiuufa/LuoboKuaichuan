#include "qr.h"

#include <sstream>

#include "qrcodegen.hpp"

namespace luobo {

std::string qrSvg(const std::string& data, const std::string& foreground, const std::string& background,
                  int outputSize, int margin) {
    using qrcodegen::QrCode;
    const QrCode qr = QrCode::encodeText(data.c_str(), QrCode::Ecc::MEDIUM);
    const int n = qr.getSize();
    if (margin < 0) margin = 0;
    const int total = n + margin * 2;

    std::ostringstream path;
    for (int y = 0; y < n; ++y) {
        int x = 0;
        while (x < n) {
            if (!qr.getModule(x, y)) {
                ++x;
                continue;
            }
            int run = 1;
            while (x + run < n && qr.getModule(x + run, y)) ++run;
            path << 'M' << (x + margin) << ' ' << (y + margin) << 'h' << run << "v1H" << (x + margin)
                 << 'z';
            x += run;
        }
    }

    std::ostringstream svg;
    svg << "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" << outputSize << "\" height=\""
        << outputSize << "\" viewBox=\"0 0 " << total << ' ' << total
        << "\" shape-rendering=\"crispEdges\">"
        << "<rect width=\"" << total << "\" height=\"" << total << "\" fill=\"" << background
        << "\"/><path fill=\"" << foreground << "\" d=\"" << path.str() << "\"/></svg>";
    return svg.str();
}

}  // namespace luobo
