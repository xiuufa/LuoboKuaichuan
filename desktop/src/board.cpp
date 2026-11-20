#include "board.h"

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <random>
#include <sstream>
#include <system_error>

#include "json.hpp"

namespace fs = std::filesystem;
using nlohmann::json;

namespace luobo {
namespace {

std::string utf8Take(const std::string& s, size_t maxBytes) {
    if (s.size() <= maxBytes) return s;
    size_t n = maxBytes;
    while (n > 0 && (static_cast<unsigned char>(s[n]) & 0xC0) == 0x80) --n;
    return s.substr(0, n);
}

std::string jsonString(const std::string& s) { return json(s).dump(); }

}  // namespace

std::string BoardItem::toJson() const {
    std::ostringstream out;
    out << '{'
        << "\"id\":" << jsonString(id)
        << ",\"kind\":" << jsonString(kind)
        << ",\"name\":" << jsonString(name)
        << ",\"text\":" << jsonString(text)
        << ",\"size\":" << size
        << ",\"mime\":" << jsonString(mime)
        << ",\"sender\":" << jsonString(sender)
        << ",\"senderId\":" << jsonString(senderId)
        << ",\"createdAt\":" << createdAt
        << ",\"state\":" << jsonString(state)
        << ",\"received\":" << received
        << ",\"fileName\":" << jsonString(fileName)
        << '}';
    return out.str();
}

BoardRepository::BoardRepository(fs::path dir, size_t maxItems)
    : dir_(std::move(dir)), maxItems_(maxItems) {
    std::error_code ec;
    fs::create_directories(dir_, ec);
    std::lock_guard<std::mutex> lock(mu_);
    loadLocked();
}

int64_t BoardRepository::nowMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

std::string BoardRepository::newIdLocked() {
    static std::mt19937_64 rng(std::random_device{}());
    static const char* hex = "0123456789abcdef";
    std::string id;
    id.reserve(12);
    for (int i = 0; i < 12; ++i) id.push_back(hex[rng() & 0xF]);
    return id;
}

std::string BoardRepository::sanitizeFileName(const std::string& raw) {
    std::string name = raw;
    const auto slash = name.find_last_of("/\\");
    if (slash != std::string::npos) name = name.substr(slash + 1);
    std::string out;
    out.reserve(name.size());
    for (char ch : name) {
        const unsigned char c = static_cast<unsigned char>(ch);
        const bool illegal = c < 0x20 || ch == '\\' || ch == '/' || ch == ':' || ch == '*' ||
                             ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|';
        out.push_back(illegal ? '_' : ch);
    }
    if (out.empty() || out == "." || out == "..") out = "file";
    if (out.size() > 120) out = utf8Take(out, 120);
    return out;
}

std::string BoardRepository::uniqueFileNameLocked(const std::string& rawName) const {
    const std::string safe = sanitizeFileName(rawName);
    const fs::path base(safe);
    std::string stem = base.stem().string();
    std::string ext = base.extension().string();
    if (stem.empty()) stem = "file";
    std::string candidate = safe;
    int n = 1;
    while (fs::exists(dir_ / candidate)) {
        candidate = stem + " (" + std::to_string(n) + ")" + ext;
        ++n;
        if (n > 9999) break;
    }
    return candidate;
}

void BoardRepository::loadLocked() {
    const fs::path store = dir_ / "board.json";
    if (!fs::exists(store)) return;
    std::ifstream in(store);
    if (!in) return;
    json root;
    try {
        in >> root;
    } catch (...) {
        return;
    }
    version_ = root.value("version", 0ULL);
    items_.clear();
    index_.clear();
    if (!root.contains("items") || !root["items"].is_array()) return;
    for (const auto& j : root["items"]) {
        BoardItem item;
        item.id = j.value("id", "");
        item.kind = j.value("kind", "");
        item.name = j.value("name", "");
        item.text = j.value("text", "");
        item.size = j.value("size", 0ULL);
        item.mime = j.value("mime", "application/octet-stream");
        item.sender = j.value("sender", "");
        item.senderId = j.value("senderId", "");
        item.createdAt = j.value("createdAt", static_cast<int64_t>(0));
        item.state = j.value("state", std::string(kStateReady));
        item.received = j.value("received", 0ULL);
        item.fileName = j.value("fileName", "");
        if (item.id.empty()) continue;
        if (item.kind == kKindFile) {
            const bool exists = !item.fileName.empty() && fs::exists(dir_ / item.fileName);
            if (!exists) {
                if (item.state == kStateReceiving) continue;  // 半成品直接丢弃
                item.state = kStateFailed;
            }
        }
        index_[item.id] = items_.size();
        items_.push_back(std::move(item));
    }
}

void BoardRepository::persistLocked() {
    json root;
    root["version"] = version_;
    root["items"] = json::array();
    for (const auto& item : items_) {
        json j;
        j["id"] = item.id;
        j["kind"] = item.kind;
        j["name"] = item.name;
        j["text"] = item.text;
        j["size"] = item.size;
        j["mime"] = item.mime;
        j["sender"] = item.sender;
        j["senderId"] = item.senderId;
        j["createdAt"] = item.createdAt;
        j["state"] = item.state;
        j["received"] = item.received;
        j["fileName"] = item.fileName;
        root["items"].push_back(std::move(j));
    }
    const fs::path store = dir_ / "board.json";
    const fs::path tmp = dir_ / "board.json.tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) return;
        out << root.dump(-1, ' ', false, json::error_handler_t::replace);
    }
    std::error_code ec;
    fs::rename(tmp, store, ec);
    if (ec) {
        fs::remove(store, ec);
        fs::rename(tmp, store, ec);
    }
}

void BoardRepository::removeItemLocked(size_t index) {
    if (index >= items_.size()) return;
    const BoardItem& item = items_[index];
    if (item.kind == kKindFile && !item.fileName.empty()) {
        std::error_code ec;
        fs::remove(dir_ / item.fileName, ec);
    }
    items_.erase(items_.begin() + static_cast<std::ptrdiff_t>(index));
    index_.clear();
    for (size_t i = 0; i < items_.size(); ++i) index_[items_[i].id] = i;
}

uint64_t BoardRepository::currentVersion() {
    std::lock_guard<std::mutex> lock(mu_);
    return version_;
}

size_t BoardRepository::size() {
    std::lock_guard<std::mutex> lock(mu_);
    return items_.size();
}

std::vector<BoardItem> BoardRepository::listNewestFirst() {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<BoardItem> out(items_.rbegin(), items_.rend());
    return out;
}

std::optional<BoardItem> BoardRepository::get(const std::string& id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return std::nullopt;
    return items_[it->second];
}

std::optional<fs::path> BoardRepository::filePathFor(const std::string& id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return std::nullopt;
    const BoardItem& item = items_[it->second];
    if (item.kind != kKindFile || item.fileName.empty()) return std::nullopt;
    return (dir_ / item.fileName).string();
}

BoardItem BoardRepository::addText(const std::string& text, const std::string& sender,
                                   const std::string& senderId) {
    std::lock_guard<std::mutex> lock(mu_);
    BoardItem item;
    item.id = newIdLocked();
    item.kind = kKindText;
    item.name = utf8Take(text, 80);
    item.text = text;
    item.size = text.size();
    item.mime = "text/plain; charset=utf-8";
    item.sender = sender;
    item.senderId = senderId;
    item.createdAt = nowMillis();
    item.state = kStateReady;
    item.received = item.size;
    index_[item.id] = items_.size();
    items_.push_back(item);
    ++version_;
    while (items_.size() > maxItems_) removeItemLocked(0);
    persistLocked();
    return item;
}

std::pair<BoardItem, std::string> BoardRepository::beginFile(const std::string& name, uint64_t size,
                                                             const std::string& mime,
                                                             const std::string& sender,
                                                             const std::string& senderId) {
    std::lock_guard<std::mutex> lock(mu_);
    BoardItem item;
    item.id = newIdLocked();
    item.kind = kKindFile;
    item.name = sanitizeFileName(name);
    item.size = size;
    item.mime = mime.empty() ? "application/octet-stream" : mime;
    item.sender = sender;
    item.senderId = senderId;
    item.createdAt = nowMillis();
    item.state = kStateReceiving;
    item.received = 0;
    item.fileName = item.id + "_" + item.name;
    const fs::path path = dir_ / item.fileName;
    { std::ofstream out(path, std::ios::binary | std::ios::trunc); }
    index_[item.id] = items_.size();
    items_.push_back(item);
    ++version_;
    while (items_.size() > maxItems_) removeItemLocked(0);
    persistLocked();
    return {item, path.string()};
}

uint64_t BoardRepository::appendReceived(const std::string& id, uint64_t bytes) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return 0;
    auto& item = items_[it->second];
    item.received += bytes;
    return item.received;
}

std::optional<BoardItem> BoardRepository::completeFile(const std::string& id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return std::nullopt;
    auto& item = items_[it->second];
    if (item.kind != kKindFile) return std::nullopt;
    if (item.state == kStateReady) return item;
    const fs::path oldPath = dir_ / item.fileName;
    const std::string finalName = uniqueFileNameLocked(item.name);
    std::error_code ec;
    fs::rename(oldPath, dir_ / finalName, ec);
    if (ec) {
        fs::copy_file(oldPath, dir_ / finalName, fs::copy_options::overwrite_existing, ec);
        if (!ec) fs::remove(oldPath, ec);
    }
    if (!ec) item.fileName = finalName;
    item.state = kStateReady;
    item.received = item.size;
    ++version_;
    persistLocked();
    return item;
}

std::optional<BoardItem> BoardRepository::failFile(const std::string& id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return std::nullopt;
    const BoardItem item = items_[it->second];
    removeItemLocked(it->second);
    ++version_;
    persistLocked();
    return item;
}

bool BoardRepository::deleteItem(const std::string& id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return false;
    removeItemLocked(it->second);
    ++version_;
    persistLocked();
    return true;
}

std::optional<BoardItem> BoardRepository::resend(const std::string& id, const std::string& sender,
                                                 const std::string& senderId) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return std::nullopt;
    const BoardItem original = items_[it->second];
    BoardItem item;
    item.id = newIdLocked();
    item.kind = original.kind;
    item.name = original.name;
    item.mime = original.mime;
    item.sender = sender;
    item.senderId = senderId;
    item.createdAt = nowMillis();
    item.state = kStateReady;
    if (original.isText()) {
        item.text = original.text;
        item.size = original.size;
        item.received = item.size;
    } else {
        const fs::path src = dir_ / original.fileName;
        if (!fs::exists(src)) return std::nullopt;
        const std::string target = uniqueFileNameLocked(original.name);
        std::error_code ec;
        fs::copy_file(src, dir_ / target, fs::copy_options::overwrite_existing, ec);
        if (ec) return std::nullopt;
        item.fileName = target;
        item.size = original.size;
        item.received = item.size;
    }
    index_[item.id] = items_.size();
    items_.push_back(item);
    ++version_;
    while (items_.size() > maxItems_) removeItemLocked(0);
    persistLocked();
    return item;
}

bool BoardRepository::deleteFileOnly(const std::string& id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = index_.find(id);
    if (it == index_.end()) return false;
    auto& item = items_[it->second];
    if (item.kind != kKindFile) return false;
    if (!item.fileName.empty()) {
        std::error_code ec;
        fs::remove(dir_ / item.fileName, ec);
    }
    item.state = kStateFailed;
    ++version_;
    persistLocked();
    return true;
}

size_t BoardRepository::clear() {
    std::lock_guard<std::mutex> lock(mu_);
    const size_t removed = items_.size();
    for (const auto& item : items_) {
        if (item.kind == kKindFile && !item.fileName.empty()) {
            std::error_code ec;
            fs::remove(dir_ / item.fileName, ec);
        }
    }
    items_.clear();
    index_.clear();
    ++version_;
    persistLocked();
    return removed;
}

}  // namespace luobo
