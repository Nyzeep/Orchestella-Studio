#pragma once

#include <string>
#include <vector>

namespace fl {

/**
 * Lexical check for a path that must stay under a trusted root: relative, no
 * empty/./.. components, no NUL. It does not touch the filesystem; symlinks are
 * rejected separately by StorageLayout.
 */
bool is_safe_relative_path(const std::string& path);

std::vector<std::string> split_relative_path(const std::string& path);

std::string join_path(const std::string& base, const std::string& relative);

/** True when child is base or lies below it, component-wise (no prefix tricks). */
bool is_within(const std::string& base, const std::string& child);

std::string parent_of(const std::string& path);

std::string base_name(const std::string& path);

}  // namespace fl
