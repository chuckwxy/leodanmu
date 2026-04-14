#include "secure_util.h"
#include "sha256_lite.h"

#include <zlib.h>

#include <stdexcept>

std::vector<uint8_t> sha256_bytes(const std::vector<uint8_t>& input) {
    std::vector<uint8_t> out(32);
    SHA256_CTX_LITE ctx{};
    sha256_lite_init(&ctx);
    sha256_lite_update(&ctx, input.data(), input.size());
    sha256_lite_final(&ctx, out.data());
    return out;
}

std::vector<uint8_t> zlib_decompress_bytes(const std::vector<uint8_t>& input) {
    z_stream zs{};
    if (inflateInit(&zs) != Z_OK) throw std::runtime_error("inflateInit failed");

    zs.next_in = const_cast<Bytef*>(reinterpret_cast<const Bytef*>(input.data()));
    zs.avail_in = static_cast<uInt>(input.size());

    std::vector<uint8_t> out;
    uint8_t buffer[8192];
    int ret;
    do {
        zs.next_out = buffer;
        zs.avail_out = sizeof(buffer);
        ret = inflate(&zs, 0);
        if (out.size() < zs.total_out) {
            out.insert(out.end(), buffer, buffer + (zs.total_out - out.size()));
        }
    } while (ret == Z_OK);

    inflateEnd(&zs);
    if (ret != Z_STREAM_END) throw std::runtime_error("inflate failed");
    return out;
}

std::vector<uint8_t> xor_with_key(const std::vector<uint8_t>& input, const std::vector<uint8_t>& key) {
    std::vector<uint8_t> out(input);
    for (size_t i = 0; i < out.size(); ++i) {
        out[i] = static_cast<uint8_t>(out[i] ^ key[i % key.size()]);
    }
    return out;
}

std::string bytes_to_string(const std::vector<uint8_t>& input) {
    return std::string(input.begin(), input.end());
}

std::vector<uint8_t> string_to_bytes(const std::string& input) {
    return std::vector<uint8_t>(input.begin(), input.end());
}

uint32_t read_u32_be(const uint8_t* ptr) {
    return (static_cast<uint32_t>(ptr[0]) << 24) |
           (static_cast<uint32_t>(ptr[1]) << 16) |
           (static_cast<uint32_t>(ptr[2]) << 8) |
           static_cast<uint32_t>(ptr[3]);
}
