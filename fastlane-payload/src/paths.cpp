#include "fastlane/paths.h"

#include <cstddef>

namespace fl {

bool is_safe_relative_path(const std::string& path) {
    if (path.empty() || path.size() > 4096 || path.front() == '/') {
        return false;
    }
    if (path.find('\0') != std::string::npos) {
        return false;
    }
    size_t start = 0;
    for (;;) {
        const size_t slash = path.find('/', start);
        const size_t end = slash == std::string::npos ? path.size() : slash;
        if (end == start) {
            return false;
        }
        const std::string component = path.substr(start, end - start);
        if (component == "." || component == "..") {
            return false;
        }
        if (slash == std::string::npos) {
            return true;
        }
        start = slash + 1;
        if (start == path.size()) {
            return false;
        }
    }
}

std::vector<std::string> split_relative_path(const std::string& path) {
    if (!is_safe_relative_path(path)) {
        return {};
    }
    std::vector<std::string> components;
    size_t start = 0;
    for (;;) {
        const size_t slash = path.find('/', start);
        if (slash == std::string::npos) {
            components.push_back(path.substr(start));
            return components;
        }
        components.push_back(path.substr(start, slash - start));
        start = slash + 1;
    }
}

std::string join_path(const std::string& base, const std::string& relative) {
    if (base.empty()) {
        return relative;
    }
    if (relative.empty()) {
        return base;
    }
    if (base.back() == '/') {
        return base + relative;
    }
    return base + "/" + relative;
}

bool is_within(const std::string& base, const std::string& child) {
    if (base.empty()) {
        return false;
    }
    if (child == base) {
        return true;
    }
    const std::string prefix = base.back() == '/' ? base : base + "/";
    return child.size() > prefix.size() && child.compare(0, prefix.size(), prefix) == 0;
}

std::string parent_of(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) {
        return {};
    }
    if (slash == 0) {
        return "/";
    }
    return path.substr(0, slash);
}

std::string base_name(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

}  // namespace fl
