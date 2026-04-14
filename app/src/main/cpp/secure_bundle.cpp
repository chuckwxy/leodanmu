#include "secure_bundle.h"

std::vector<uint8_t> merge_bundle_parts(const std::vector<std::vector<uint8_t>>& parts) {
    std::vector<uint8_t> merged;
    for (const auto& part : parts) {
        merged.insert(merged.end(), part.begin(), part.end());
    }
    return merged;
}
