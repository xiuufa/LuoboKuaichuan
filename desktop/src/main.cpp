// 萝卜快传 · 桌面端（Windows exe / Linux 二进制 / AppImage 共用）
//
// 与安卓端、网页端共用同一套 HTTP API 与网页 UI：
//   启动本地 HTTP 服务（默认 8080）→ 自动打开浏览器 → 网页里收发文本/文件。
// 数据目录默认是程序旁边的 Luobo/，收到的文件直接落盘到里面。
#include <algorithm>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// 大分片 PUT 可能带 application/x-www-form-urlencoded，放宽 httplib 的表单体积上限
#define CPPHTTPLIB_FORM_URL_ENCODED_PAYLOAD_MAX_LENGTH (64 * 1024 * 1024)

#include "board.h"
#include "httplib.h"
#include "json.hpp"
#include "qr.h"
#include "web_assets.h"

#ifdef _WIN32
#include <windows.h>
#include <iphlpapi.h>
#include <shellapi.h>
#else
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <netinet/in.h>
#include <sys/types.h>
#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>
#endif

namespace fs = std::filesystem;
using nlohmann::json;

namespace {

constexpr int kDefaultPort = 8080;
constexpr uint64_t kChunkSize = 1024ULL * 1024ULL;
constexpr uint64_t kMaxTextBytes = 1024ULL * 1024ULL;
constexpr uint64_t kMaxFileBytes = 16ULL * 1024ULL * 1024ULL * 1024ULL;
constexpr uint64_t kBigFileThreshold = 20ULL * 1024ULL * 1024ULL;
constexpr const char* kAppName = "萝卜快传";
constexpr const char* kVersionName = "1.0.0";
constexpr const char* kApiApp = "LuoboKuaichuan";

int64_t nowMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

std::string toUtf8(const fs::path& path) {
#ifdef _WIN32
    return path.u8string();
#else
    return path.string();
#endif
}

fs::path pathFromUtf8(const std::string& text) {
#ifdef _WIN32
    return fs::u8path(text);
#else
    return fs::path(text);
#endif
}

std::string exeDir() {
    // AppImage 运行时：数据目录应放在 .AppImage 文件旁边，而不是临时挂载/解包目录
    const char* appImagePath = std::getenv("APPIMAGE");
    if (appImagePath && *appImagePath) {
        return toUtf8(pathFromUtf8(appImagePath).parent_path());
    }
#ifdef _WIN32
    wchar_t buf[MAX_PATH] = {0};
    GetModuleFileNameW(nullptr, buf, MAX_PATH);
    return toUtf8(fs::path(buf).parent_path());
#else
    char buf[4096] = {0};
    const ssize_t len = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (len > 0) return fs::path(std::string(buf, static_cast<size_t>(len))).parent_path().string();
    return fs::current_path().string();
#endif
}

std::string hostName() {
#ifdef _WIN32
    char buf[256] = {0};
    DWORD size = sizeof(buf);
    if (GetComputerNameA(buf, &size)) return std::string(buf, size);
    return "Windows PC";
#else
    char buf[256] = {0};
    if (gethostname(buf, sizeof(buf) - 1) == 0) return std::string(buf);
    return "Linux PC";
#endif
}

std::string urlEncode(const std::string& in) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    out.reserve(in.size() * 3);
    for (unsigned char c : in) {
        const bool unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                                (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
        if (unreserved) {
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back('%');
            out.push_back(hex[c >> 4]);
            out.push_back(hex[c & 0xF]);
        }
    }
    return out;
}

std::string mimeFor(const std::string& fileName) {
    const auto dot = fileName.find_last_of('.');
    std::string ext;
    if (dot != std::string::npos) {
        ext = fileName.substr(dot + 1);
        std::transform(ext.begin(), ext.end(), ext.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    }
    static const std::unordered_map<std::string, std::string> kMime = {
        {"html", "text/html; charset=utf-8"}, {"htm", "text/html; charset=utf-8"},
        {"css", "text/css; charset=utf-8"},   {"js", "application/javascript; charset=utf-8"},
        {"json", "application/json; charset=utf-8"}, {"txt", "text/plain; charset=utf-8"},
        {"md", "text/markdown; charset=utf-8"}, {"csv", "text/csv; charset=utf-8"},
        {"svg", "image/svg+xml"},             {"png", "image/png"},
        {"jpg", "image/jpeg"},                {"jpeg", "image/jpeg"},
        {"gif", "image/gif"},                 {"webp", "image/webp"},
        {"bmp", "image/bmp"},                 {"ico", "image/x-icon"},
        {"mp3", "audio/mpeg"},                {"wav", "audio/wav"},
        {"m4a", "audio/mp4"},                 {"ogg", "audio/ogg"},
        {"mp4", "video/mp4"},                 {"mkv", "video/x-matroska"},
        {"webm", "video/webm"},               {"mov", "video/quicktime"},
        {"avi", "video/x-msvideo"},           {"pdf", "application/pdf"},
        {"zip", "application/zip"},           {"rar", "application/vnd.rar"},
        {"7z", "application/x-7z-compressed"},{"tar", "application/x-tar"},
        {"gz", "application/gzip"},           {"apk", "application/vnd.android.package-archive"},
        {"exe", "application/vnd.microsoft.portable-executable"},
        {"msi", "application/x-msi"},         {"deb", "application/vnd.debian.binary-package"},
        {"appimage", "application/x-executable"}, {"iso", "application/x-iso9660-image"},
        {"doc", "application/msword"},
        {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
        {"xls", "application/vnd.ms-excel"},
        {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
        {"ppt", "application/vnd.ms-powerpoint"},
        {"pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
        {"epub", "application/epub+zip"},     {"ttf", "font/ttf"},
        {"otf", "font/otf"},                  {"woff2", "font/woff2"},
        {"log", "text/plain; charset=utf-8"}, {"sh", "text/x-shellscript"},
    };
    const auto it = kMime.find(ext);
    return it == kMime.end() ? "application/octet-stream" : it->second;
}

// 枚举本机局域网 IPv4（排除回环），优先 192.168 / 10. / 172.16-31
std::vector<std::string> localUrls(int port) {
    std::vector<std::string> ips;
#ifdef _WIN32
    ULONG size = 0;
    GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                      GAA_FLAG_SKIP_DNS_SERVER,
                         nullptr, nullptr, &size);
    std::vector<char> buf(size ? size : 1);
    auto* addrs = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                           GAA_FLAG_SKIP_DNS_SERVER,
                             nullptr, addrs, &size) == NO_ERROR) {
        for (auto* a = addrs; a; a = a->Next) {
            if (a->OperStatus != IfOperStatusUp) continue;
            for (auto* uni = a->FirstUnicastAddress; uni; uni = uni->Next) {
                if (!uni->Address.lpSockaddr) continue;
                if (uni->Address.lpSockaddr->sa_family != AF_INET) continue;
                auto* sin = reinterpret_cast<sockaddr_in*>(uni->Address.lpSockaddr);
                char ip[INET_ADDRSTRLEN] = {0};
                if (inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof(ip))) ips.push_back(ip);
            }
        }
    }
#else
    ifaddrs* addrs = nullptr;
    if (getifaddrs(&addrs) == 0) {
        for (ifaddrs* a = addrs; a; a = a->ifa_next) {
            if (!a->ifa_addr || a->ifa_addr->sa_family != AF_INET) continue;
            if (!(a->ifa_flags & IFF_UP)) continue;
            auto* sin = reinterpret_cast<sockaddr_in*>(a->ifa_addr);
            char ip[INET_ADDRSTRLEN] = {0};
            if (!inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof(ip))) continue;
            ips.push_back(ip);
        }
        freeifaddrs(addrs);
    }
#endif
    ips.erase(std::remove_if(ips.begin(), ips.end(),
                             [](const std::string& ip) {
                                 return ip.rfind("127.", 0) == 0 || ip.rfind("169.254.", 0) == 0 ||
                                        ip == "0.0.0.0";
                             }),
              ips.end());
    std::stable_sort(ips.begin(), ips.end(), [](const std::string& a, const std::string& b) {
        auto rank = [](const std::string& ip) {
            if (ip.rfind("192.168.", 0) == 0) return 0;
            if (ip.rfind("10.", 0) == 0) return 0;
            if (ip.rfind("172.", 0) == 0) return 1;
            return 2;
        };
        return rank(a) < rank(b);
    });
    std::vector<std::string> urls;
    urls.reserve(ips.size());
    for (const auto& ip : ips) urls.push_back("http://" + ip + ":" + std::to_string(port));
    return urls;
}

bool isLoopback(const std::string& addr) {
    return addr == "127.0.0.1" || addr == "::1" || addr == "::ffff:127.0.0.1" ||
           addr.rfind("127.", 0) == 0;
}

void openBrowser(const std::string& url) {
#ifdef _WIN32
    ShellExecuteA(nullptr, "open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
#else
    const pid_t pid = fork();
    if (pid == 0) {
        execlp("xdg-open", "xdg-open", url.c_str(), static_cast<char*>(nullptr));
        _exit(127);
    }
#endif
}

// 文件锁：避免同一数据目录被两个实例同时写
// 直接尝试 bind 探测端口是否空闲（httplib 默认开 SO_REUSEPORT，单靠它 bind 不会报错）
bool portIsFree(int port) {
#ifdef _WIN32
    SOCKET probe = ::socket(AF_INET, SOCK_STREAM, 0);
    if (probe == INVALID_SOCKET) return false;
    BOOL reuse = TRUE;
    ::setsockopt(probe, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<u_short>(port));
    const bool free = ::bind(probe, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0;
    ::closesocket(probe);
    return free;
#else
    const int probe = ::socket(AF_INET, SOCK_STREAM, 0);
    if (probe < 0) return false;
    const int reuse = 1;
    ::setsockopt(probe, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    const bool free = ::bind(probe, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0;
    ::close(probe);
    return free;
#endif
}

class InstanceLock {
public:
    explicit InstanceLock(const fs::path& dir) : path_(dir / ".luobo.lock") {
#ifdef _WIN32
        handle_ = CreateFileW(path_.wstring().c_str(), GENERIC_READ | GENERIC_WRITE, 0,
                              nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        ok_ = handle_ != INVALID_HANDLE_VALUE;
#else
        fd_ = open(path_.c_str(), O_CREAT | O_RDWR, 0644);
        // 同数据目录只允许一个实例：拿不到非阻塞排他锁说明已有实例在跑
        ok_ = (fd_ >= 0) && (flock(fd_, LOCK_EX | LOCK_NB) == 0);
#endif
    }
    ~InstanceLock() {
#ifdef _WIN32
        if (ok_) CloseHandle(handle_);
#else
        if (ok_) close(fd_);
#endif
    }
    bool ok() const { return ok_; }

private:
    fs::path path_;
    bool ok_ = false;
#ifdef _WIN32
    HANDLE handle_ = INVALID_HANDLE_VALUE;
#else
    int fd_ = -1;
#endif
};

class Logger {
public:
    void init(const fs::path& file) {
        std::lock_guard<std::mutex> lock(mu_);
        file_ = file;
    }
    void log(const std::string& text) {
        std::lock_guard<std::mutex> lock(mu_);
        const std::string line = "[" + std::to_string(nowMillis()) + "] " + text + "\n";
        std::fputs(line.c_str(), stderr);
        if (file_.empty()) return;
        std::ofstream out(file_, std::ios::app | std::ios::binary);
        if (out) out << line;
        std::error_code ec;
        if (fs::file_size(file_, ec) > 512 * 1024 && !ec) {
            fs::rename(file_, file_.string() + ".1", ec);
        }
    }

private:
    std::mutex mu_;
    fs::path file_;
};

struct UploadSession {
    std::string uploadId;
    std::string itemId;
    std::string path;
    uint64_t size = 0;
    uint64_t received = 0;
};

class App {
public:
    App(fs::path dataDir, int port, std::string host, bool openBrowser_)
        : dataDir_(std::move(dataDir)),
          port_(port),
          host_(std::move(host)),
          openBrowserAtStart_(openBrowser_) {
        fs::create_directories(dataDir_);
        logger_.init(dataDir_ / "luobo.log");
        repo_ = std::make_unique<luobo::BoardRepository>(dataDir_);
        deviceName_ = hostName();
        deviceId_ = loadOrCreateDeviceId();
        startedAt_ = nowMillis();
        // httplib 默认用 SO_REUSEPORT，会让两个进程同时监听同一端口；
        // 这里只保留 SO_REUSEADDR，端口冲突才能被正确发现。
        svr_.set_socket_options([](auto sock) {
            httplib::set_socket_opt(sock, SOL_SOCKET, SO_REUSEADDR, 1);
        });
        setupRoutes();
    }

    bool run() {
        bool bound = false;
        for (int candidate = port_; candidate < port_ + 20; ++candidate) {
            if (!portIsFree(candidate)) continue;
            if (svr_.bind_to_port(host_, candidate)) {
                port_ = candidate;
                bound = true;
                break;
            }
        }
        if (!bound) return false;
        writePortFile();
        logger_.log("HTTP 服务已启动: " + preferredUrl());
        if (openBrowserAtStart_) openBrowser(preferredUrl());
        if (shuttingDown_) {
            logger_.log("启动过程中收到关闭信号，跳过监听");
            return true;
        }
        svr_.listen_after_bind();
        logger_.log("HTTP 服务已停止");
        return true;
    }

    int port() const { return port_; }

    /** 幂等关闭：停止按钮、Ctrl+C、SIGTERM、系统关机等信号重复到达时只执行一次。 */
    void requestShutdown() {
        if (shuttingDown_.exchange(true)) return;
        logger_.log("收到关闭信号，正在优雅关闭…");
        std::thread([this] {
            std::this_thread::sleep_for(std::chrono::milliseconds(120));
            svr_.stop();
        }).detach();
    }

private:
    std::string preferredUrl() const {
        auto urls = localUrls(port_);
        if (!urls.empty()) return urls.front();
        return "http://127.0.0.1:" + std::to_string(port_);
    }

    void writePortFile() const {
        std::ofstream out(dataDir_ / ".luobo.port", std::ios::trunc);
        if (out) out << port_;
    }

    std::string loadOrCreateDeviceId() {
        const fs::path idPath = dataDir_ / ".luobo_id";
        std::ifstream in(idPath);
        std::string id;
        if (in) std::getline(in, id);
        if (id.size() >= 8) return id;
        static const char* hex = "0123456789abcdef";
        std::mt19937 rng(static_cast<unsigned>(nowMillis() & 0xffffffff));
        id.clear();
        for (int i = 0; i < 16; ++i) id.push_back(hex[rng() & 0xF]);
        std::ofstream out(idPath, std::ios::trunc);
        if (out) out << id;
        return id;
    }

    std::string infoJson() {
        const auto urls = localUrls(port_);
        json u = json::array();
        for (const auto& url : urls) u.push_back(url);
        json features = json::array(
            {"text", "file", "chunked-upload", "range-download", "qr", "shared-board", "shutdown"});
        json out;
        out["ok"] = true;
        out["app"] = kApiApp;
        out["apiVersion"] = 1;
        out["appName"] = kAppName;
        out["versionName"] = kVersionName;
        out["deviceName"] = deviceName_;
        out["deviceId"] = deviceId_;
        out["port"] = port_;
        out["url"] = urls.empty() ? "http://127.0.0.1:" + std::to_string(port_) : urls.front();
        out["urls"] = u;
        out["boardVersion"] = repo_->currentVersion();
        out["itemCount"] = repo_->size();
        out["maxTextBytes"] = kMaxTextBytes;
        out["maxUploadBytes"] = kMaxFileBytes;
        out["chunkSize"] = kChunkSize;
        out["bigFileThreshold"] = kBigFileThreshold;
        out["storagePath"] = toUtf8(dataDir_);
        out["features"] = features;
        out["uptimeSeconds"] = (nowMillis() - startedAt_) / 1000;
        return out.dump();
    }

    static void jsonResponse(httplib::Response& res, const std::string& body, int status = 200) {
        res.status = status;
        res.set_content(body, "application/json; charset=utf-8");
    }
    static void okResponse(httplib::Response& res, const json& body) {
        jsonResponse(res, body.dump());
    }
    static void errorResponse(httplib::Response& res, int status, const std::string& message) {
        jsonResponse(res, json{{"ok", false}, {"error", message}}.dump(), status);
    }

    std::optional<json> parseBody(const httplib::Request& req, httplib::Response& res) {
        try {
            json j = json::parse(req.body);
            if (!j.is_object()) throw std::runtime_error("body must be object");
            return j;
        } catch (const std::exception& e) {
            errorResponse(res, 400, "invalid json");
            return std::nullopt;
        }
    }

    void setupRoutes() {
        svr_.set_payload_max_length(static_cast<size_t>(64ULL * 1024 * 1024));
        svr_.set_post_routing_handler([](const httplib::Request&, httplib::Response& res) {
            res.set_header("Access-Control-Allow-Origin", "*");
            res.set_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, HEAD, OPTIONS");
            res.set_header("Access-Control-Allow-Headers",
                           "Range, Content-Type, Content-Length, Accept, X-Requested-With");
            res.set_header("Access-Control-Expose-Headers",
                           "Content-Range, Content-Length, Accept-Ranges, Content-Disposition");
            res.set_header("Access-Control-Max-Age", "86400");
            res.set_header("X-Content-Type-Options", "nosniff");
        });

        svr_.Options(R"(.*)", [](const httplib::Request&, httplib::Response& res) {
            res.status = 204;
        });

        svr_.Get("/api/info", [this](const httplib::Request&, httplib::Response& res) {
            jsonResponse(res, infoJson());
        });

        svr_.Get("/api/list", [this](const httplib::Request& req, httplib::Response& res) {
            const uint64_t version = repo_->currentVersion();
            bool unchanged = false;
            if (req.has_param("since")) {
                try {
                    unchanged = std::stoull(req.get_param_value("since")) == version;
                } catch (...) {
                    unchanged = false;
                }
            }
            json items = json::array();
            for (const auto& item : repo_->listNewestFirst()) items.push_back(json::parse(item.toJson()));
            json body;
            body["ok"] = true;
            body["version"] = version;
            body["itemCount"] = items.size();
            body["serverTime"] = nowMillis();
            body["unchanged"] = unchanged;
            body["items"] = std::move(items);
            okResponse(res, body);
        });

        svr_.Post("/api/text", [this](const httplib::Request& req, httplib::Response& res) {
            auto body = parseBody(req, res);
            if (!body) return;
            const std::string text = body->value("text", "");
            if (text.empty()) {
                errorResponse(res, 400, "text required");
                return;
            }
            if (text.size() > kMaxTextBytes) {
                errorResponse(res, 413, "text too large");
                return;
            }
            const std::string sender = body->value("sender", "桌面端");
            const std::string senderId = body->value("senderId", deviceId_);
            const auto item = repo_->addText(text, sender, senderId);
            json out;
            out["ok"] = true;
            out["item"] = json::parse(item.toJson());
            out["version"] = repo_->currentVersion();
            okResponse(res, out);
        });

        svr_.Post("/api/upload/init", [this](const httplib::Request& req, httplib::Response& res) {
            auto body = parseBody(req, res);
            if (!body) return;
            const std::string name = body->value("name", "");
            const uint64_t size = body->value("size", static_cast<uint64_t>(0));
            const std::string mime = body->value("mime", "application/octet-stream");
            const std::string sender = body->value("sender", "桌面端");
            const std::string senderId = body->value("senderId", deviceId_);
            if (name.empty()) {
                errorResponse(res, 400, "name required");
                return;
            }
            if (size > kMaxFileBytes) {
                errorResponse(res, 413, "file too large");
                return;
            }
            auto created = repo_->beginFile(name, size, mime, sender, senderId);
            const std::string uploadId = created.first.id;
            {
                std::lock_guard<std::mutex> lock(uploadsMu_);
                uploads_[uploadId] = UploadSession{uploadId, created.first.id, created.second, size, 0};
            }
            json out;
            out["ok"] = true;
            out["uploadId"] = uploadId;
            out["itemId"] = created.first.id;
            out["offset"] = created.first.received;
            out["size"] = size;
            out["chunkSize"] = kChunkSize;
            if (size == 0) {
                auto done = repo_->completeFile(uploadId);
                out["completed"] = true;
                if (done) out["item"] = json::parse(done->toJson());
            } else {
                out["completed"] = false;
                out["item"] = json::parse(created.first.toJson());
            }
            out["version"] = repo_->currentVersion();
            okResponse(res, out);
        });

        svr_.Put(R"(/api/upload/([^/]+))", [this](const httplib::Request& req, httplib::Response& res) {
            const std::string uploadId = req.matches[1];
            std::lock_guard<std::mutex> lock(uploadsMu_);
            const auto it = uploads_.find(uploadId);
            if (it == uploads_.end()) {
                errorResponse(res, 404, "upload not found");
                return;
            }
            auto& session = it->second;
            uint64_t offset = session.received;
            if (req.has_param("offset")) {
                try {
                    offset = std::stoull(req.get_param_value("offset"));
                } catch (...) {
                    errorResponse(res, 400, "invalid offset");
                    return;
                }
            }
            if (offset != session.received) {
                json body;
                body["ok"] = false;
                body["error"] = "offset mismatch";
                body["offset"] = session.received;
                body["size"] = session.size;
                jsonResponse(res, body.dump(), 409);
                return;
            }
            if (session.received + req.body.size() > session.size) {
                errorResponse(res, 413, "payload exceeds size");
                return;
            }
            {
                std::ofstream out(session.path, std::ios::binary | std::ios::app);
                if (!out) {
                    errorResponse(res, 500, "write failed");
                    return;
                }
                out.write(req.body.data(), static_cast<std::streamsize>(req.body.size()));
            }
            session.received += req.body.size();
            repo_->appendReceived(session.itemId, req.body.size());
            bool completed = false;
            if (session.received >= session.size && session.size > 0) {
                repo_->completeFile(session.itemId);
                completed = true;
            }
            json body;
            body["ok"] = true;
            body["received"] = session.received;
            body["size"] = session.size;
            body["completed"] = completed;
            body["version"] = repo_->currentVersion();
            okResponse(res, body);
        });

        svr_.Post(R"(/api/upload/([^/]+)/complete)",
                  [this](const httplib::Request& req, httplib::Response& res) {
                      const std::string uploadId = req.matches[1];
                      auto item = repo_->get(uploadId);
                      if (!item) {
                          errorResponse(res, 404, "upload not found");
                          return;
                      }
                      if (!item->isReady()) {
                          if (item->received < item->size) {
                              errorResponse(res, 409, "upload not complete");
                              return;
                          }
                          item = repo_->completeFile(uploadId);
                      }
                      {
                          std::lock_guard<std::mutex> lock(uploadsMu_);
                          uploads_.erase(uploadId);
                      }
                      json body;
                      body["ok"] = true;
                      body["completed"] = true;
                      body["item"] = json::parse(item->toJson());
                      body["version"] = repo_->currentVersion();
                      okResponse(res, body);
                  });

        svr_.Delete(R"(/api/upload/([^/]+))", [this](const httplib::Request& req,
                                                     httplib::Response& res) {
            const std::string uploadId = req.matches[1];
            {
                std::lock_guard<std::mutex> lock(uploadsMu_);
                uploads_.erase(uploadId);
            }
            const auto removed = repo_->failFile(uploadId);
            if (!removed) {
                errorResponse(res, 404, "upload not found");
                return;
            }
            json body;
            body["ok"] = true;
            body["version"] = repo_->currentVersion();
            okResponse(res, body);
        });

        svr_.Delete(R"(/api/item/([^/]+))", [this](const httplib::Request& req,
                                                   httplib::Response& res) {
            if (!repo_->deleteItem(req.matches[1])) {
                errorResponse(res, 404, "item not found");
                return;
            }
            json body;
            body["ok"] = true;
            body["version"] = repo_->currentVersion();
            okResponse(res, body);
        });

        svr_.Delete(R"(/api/file/([^/]+))", [this](const httplib::Request& req,
                                                   httplib::Response& res) {
            if (!repo_->deleteFileOnly(req.matches[1])) {
                errorResponse(res, 404, "file not found");
                return;
            }
            json body;
            body["ok"] = true;
            body["version"] = repo_->currentVersion();
            okResponse(res, body);
        });

        svr_.Post(R"(/api/item/([^/]+)/resend)",
                  [this](const httplib::Request& req, httplib::Response& res) {
                      const auto item = repo_->resend(req.matches[1], deviceName_, deviceId_);
                      if (!item) {
                          errorResponse(res, 404, "item not found");
                          return;
                      }
                      json body;
                      body["ok"] = true;
                      body["item"] = json::parse(item->toJson());
                      body["version"] = repo_->currentVersion();
                      okResponse(res, body);
                  });

        svr_.Post("/api/clear", [this](const httplib::Request&, httplib::Response& res) {
            const size_t removed = repo_->clear();
            json body;
            body["ok"] = true;
            body["removed"] = removed;
            body["version"] = repo_->currentVersion();
            okResponse(res, body);
        });

        svr_.Post("/api/shutdown", [this](const httplib::Request& req, httplib::Response& res) {
            if (!isLoopback(req.remote_addr)) {
                errorResponse(res, 403, "only localhost");
                return;
            }
            json body;
            body["ok"] = true;
            okResponse(res, body);
            requestShutdown();
        });

        svr_.Get(R"(/api/file/([^/]+))", [this](const httplib::Request& req, httplib::Response& res) {
            const std::string id = req.matches[1];
            auto item = repo_->get(id);
            if (!item || item->isText()) {
                errorResponse(res, 404, "file not found");
                return;
            }
            auto pathOpt = repo_->filePathFor(id);
            if (!pathOpt) {
                errorResponse(res, 404, "file not found");
                return;
            }
            const fs::path path = *pathOpt;
            std::error_code ec;
            if (!fs::exists(path, ec)) {
                errorResponse(res, 404, "file missing");
                return;
            }
            const uint64_t fileSize = fs::file_size(path, ec);
            if (ec) {
                errorResponse(res, 404, "file missing");
                return;
            }
            const std::string mime = mimeFor(item->name);
            std::string disposition = req.has_param("download") ? "attachment" : "inline";
            disposition += "; filename*=UTF-8''" + urlEncode(item->name);
            res.set_header("Content-Disposition", disposition);
            res.set_header("Content-Security-Policy", "default-src 'none'");
            // Range / 206 / 416 交给 httplib 统一处理（会按请求 Range 自动切片）
            res.set_content_provider(
                static_cast<size_t>(fileSize), mime,
                [path](size_t offset, size_t length, httplib::DataSink& sink) {
                    std::ifstream in(path, std::ios::binary);
                    if (!in) return false;
                    in.seekg(static_cast<std::streamoff>(offset));
                    std::vector<char> buf(std::min<size_t>(length, 64 * 1024));
                    size_t remaining = length;
                    while (remaining > 0 && in) {
                        const size_t want = std::min(remaining, buf.size());
                        in.read(buf.data(), static_cast<std::streamsize>(want));
                        const auto got = static_cast<size_t>(in.gcount());
                        if (got == 0) break;
                        sink.write(buf.data(), got);
                        remaining -= got;
                    }
                    return remaining == 0;
                });
        });

        svr_.Get("/api/qr.svg", [](const httplib::Request& req, httplib::Response& res) {
            if (!req.has_param("data")) {
                errorResponse(res, 400, "data required");
                return;
            }
            res.set_content(luobo::qrSvg(req.get_param_value("data")), "image/svg+xml");
        });

        // 静态网页资源（来自内嵌的 assets/web）
        svr_.Get(R"(/(.*))", [](const httplib::Request& req, httplib::Response& res) {
            std::string path = req.matches[1];
            if (path.empty() || path == "index.html") path = "index.html";
            if (path == "favicon.ico" || path == "favicon.svg") {
                res.status = 204;
                return;
            }
            const auto& files = luobo::web_assets::files();
            const auto it = files.find(path);
            if (it == files.end()) {
                res.status = 404;
                res.set_content("not found", "text/plain; charset=utf-8");
                return;
            }
            if (path.rfind("vendor/", 0) == 0) {
                res.set_header("Cache-Control", "public, max-age=31536000");
            } else {
                res.set_header("Cache-Control", "no-cache");
            }
            res.set_content(it->second, mimeFor(path));
        });
    }

    Logger logger_;
    fs::path dataDir_;
    int port_ = kDefaultPort;
    std::string host_;
    bool openBrowserAtStart_ = true;
    std::unique_ptr<luobo::BoardRepository> repo_;
    std::string deviceId_;
    std::string deviceName_;
    int64_t startedAt_ = 0;
    httplib::Server svr_;
    std::mutex uploadsMu_;
    std::unordered_map<std::string, UploadSession> uploads_;
    std::atomic<bool> shuttingDown_{false};
};

App* g_app = nullptr;

void onSignal(int) {
    if (g_app) g_app->requestShutdown();
}

#ifdef _WIN32
BOOL WINAPI onConsoleCtrl(DWORD type) {
    switch (type) {
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
        case CTRL_CLOSE_EVENT:
        case CTRL_LOGOFF_EVENT:
        case CTRL_SHUTDOWN_EVENT:
            onSignal(0);
            // 系统会在处理函数返回后强制结束进程，稍等清理线程完成
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            return TRUE;
        default:
            return FALSE;
    }
}
#endif

struct Options {
    int port = kDefaultPort;
    std::string dataDir;
    std::string host = "0.0.0.0";
    bool openBrowser = true;
};

Options parseArgs(int argc, char** argv) {
    Options opt;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i] ? argv[i] : "";
        auto next = [&](std::string& target) {
            if (i + 1 < argc && argv[i + 1]) target = argv[++i];
        };
        if ((arg == "-p" || arg == "--port") && i + 1 < argc) {
            opt.port = std::atoi(argv[++i]);
        } else if ((arg == "-d" || arg == "--data") && i + 1 < argc) {
            opt.dataDir = argv[++i];
        } else if (arg == "-H" || arg == "--host") {
            std::string value;
            next(value);
            if (!value.empty()) opt.host = value;
        } else if (arg == "--no-browser") {
            opt.openBrowser = false;
        } else if (arg == "-h" || arg == "--help") {
            std::printf("萝卜快传 桌面端 %s\n用法: luobo [--port 8080] [--data DIR] [--host 0.0.0.0] [--no-browser]\n",
                        kVersionName);
            std::exit(0);
        }
    }
    if (opt.dataDir.empty()) opt.dataDir = toUtf8(pathFromUtf8(exeDir()) / "Luobo");
    return opt;
}

int readPortFile(const fs::path& dataDir) {
    std::ifstream in(dataDir / ".luobo.port");
    int value = 0;
    if (in) in >> value;
    return (value > 0 && value < 65536) ? value : kDefaultPort;
}

void showFatal(const std::string& message) {
    std::fprintf(stderr, "%s\n", message.c_str());
#ifdef _WIN32
    MessageBoxA(nullptr, message.c_str(), "萝卜快传 - 启动失败", MB_ICONERROR | MB_OK);
#endif
}

int runApp(int argc, char** argv) {
    const Options opt = parseArgs(argc, argv);
    const fs::path dataDir = pathFromUtf8(opt.dataDir);
    std::error_code ec;
    fs::create_directories(dataDir, ec);
    if (ec && !fs::exists(dataDir)) {
        showFatal("无法创建数据目录：" + toUtf8(dataDir) + "\n" + ec.message());
        return 1;
    }
    InstanceLock lock(dataDir);
    if (!lock.ok()) {
        // 已有实例占用同一数据目录：打开它的网页即可
        const int runningPort = readPortFile(dataDir);
        openBrowser("http://127.0.0.1:" + std::to_string(runningPort) + "/");
        return 0;
    }
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
#endif
    App app(dataDir, opt.port, opt.host, opt.openBrowser);
    g_app = &app;
    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);
#ifdef _WIN32
    std::signal(SIGBREAK, onSignal);
    SetConsoleCtrlHandler(onConsoleCtrl, TRUE);
#else
    std::signal(SIGHUP, onSignal);
#endif
    if (!app.run()) {
        showFatal("端口 " + std::to_string(opt.port) + "-" +
                  std::to_string(opt.port + 19) + " 都被占用，无法启动服务。");
    }
    return 0;
}

}  // namespace

#ifdef _WIN32
int WINAPI WinMain(HINSTANCE, HINSTANCE, LPSTR, int) { return runApp(__argc, __argv); }
#else
int main(int argc, char** argv) { return runApp(argc, argv); }
#endif
