// 萝卜快传 · 桌面端共享传输板（内存数据 + board.json 持久化）
// 与安卓端 com.lobo.app.data.BoardRepository 行为保持一致，供 HTTP API 复用。
#pragma once

#include <cstdint>
#include <filesystem>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace luobo {

constexpr const char* kKindText = "text";
constexpr const char* kKindFile = "file";
constexpr const char* kStateReceiving = "receiving";
constexpr const char* kStateReady = "ready";
constexpr const char* kStateFailed = "failed";

struct BoardItem {
    std::string id;
    std::string kind;
    std::string name;
    std::string text;
    uint64_t size = 0;
    std::string mime = "text/plain";
    std::string sender;
    std::string senderId;
    int64_t createdAt = 0;
    std::string state = kStateReady;
    uint64_t received = 0;
    std::string fileName;  // 文件在数据目录中的实际文件名（仅 file 使用）

    bool isText() const { return kind == kKindText; }
    bool isReady() const { return state == kStateReady; }
    bool isReceiving() const { return state == kStateReceiving; }

    // 对外 JSON（不含 fileName，与安卓端 toJson 一致）
    std::string toJson() const;
};

class BoardRepository {
public:
    BoardRepository(std::filesystem::path dir, size_t maxItems = 1000);

    uint64_t currentVersion();
    std::vector<BoardItem> listNewestFirst();
    size_t size();
    std::optional<BoardItem> get(const std::string& id);

    // 文件完整路径（文件不存在也返回路径）；id 查不到/不是文件返回 nullopt
    std::optional<std::filesystem::path> filePathFor(const std::string& id);

    BoardItem addText(const std::string& text, const std::string& sender, const std::string& senderId);

    // 返回 {条目, 待写入的临时文件路径}
    std::pair<BoardItem, std::string> beginFile(const std::string& name, uint64_t size,
                                                const std::string& mime, const std::string& sender,
                                                const std::string& senderId);

    uint64_t appendReceived(const std::string& id, uint64_t bytes);
    std::optional<BoardItem> completeFile(const std::string& id);
    std::optional<BoardItem> failFile(const std::string& id);
    bool deleteItem(const std::string& id);
    std::optional<BoardItem> resend(const std::string& id, const std::string& sender,
                                    const std::string& senderId);
    bool deleteFileOnly(const std::string& id);
    size_t clear();

    const std::filesystem::path& dirPath() const { return dir_; }

private:
    void persistLocked();
    void loadLocked();
    std::string newIdLocked();
    std::string uniqueFileNameLocked(const std::string& rawName) const;
    void removeItemLocked(size_t index);
    static std::string sanitizeFileName(const std::string& raw);
    static int64_t nowMillis();

    mutable std::mutex mu_;
    std::filesystem::path dir_;
    size_t maxItems_;
    uint64_t version_ = 0;
    std::vector<BoardItem> items_;             // 插入顺序（旧 → 新）
    std::map<std::string, size_t> index_;      // id → items_ 下标
};

}  // namespace luobo
