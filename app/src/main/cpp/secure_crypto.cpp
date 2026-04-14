#include "secure_crypto.h"
#include "secure_util.h"

#include <stdexcept>

static const std::string kMaster = "Leo:Shell:V1";
static const std::string kMagic = "LEO1";

std::vector<uint8_t> derive_native_key(const std::string& stage,
                                       const std::string& git_commit,
                                       const std::string& payload_raw_sha256) {
    std::vector<uint8_t> seed;
    auto append = [&](const std::string& s) {
        seed.insert(seed.end(), s.begin(), s.end());
    };
    append(kMaster);
    append(git_commit);
    append(stage);
    append(payload_raw_sha256);
    return sha256_bytes(seed);
}

std::vector<uint8_t> decode_native_bundle(const std::string& stage,
                                          const std::string& git_commit,
                                          const std::string& payload_raw_sha256,
                                          const std::vector<uint8_t>& merged) {
    if (merged.size() < 8) throw std::runtime_error("merged too small");
    std::string magic(merged.begin(), merged.begin() + 4);
    if (magic != kMagic) throw std::runtime_error("bad magic");

    uint32_t header_len = read_u32_be(merged.data() + 4);
    size_t header_start = 8;
    size_t header_end = header_start + header_len;
    if (header_end > merged.size()) throw std::runtime_error("bad header len");

    std::vector<uint8_t> encrypted(merged.begin() + header_end, merged.end());
    auto key = derive_native_key(stage, git_commit, payload_raw_sha256);
    auto compressed = xor_with_key(encrypted, key);
    return zlib_decompress_bytes(compressed);
}
