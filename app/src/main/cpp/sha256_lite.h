#pragma once

#include <cstdint>
#include <cstddef>

struct SHA256_CTX_LITE {
    uint8_t data[64];
    uint32_t datalen;
    uint64_t bitlen;
    uint32_t state[8];
};

void sha256_lite_init(SHA256_CTX_LITE* ctx);
void sha256_lite_update(SHA256_CTX_LITE* ctx, const uint8_t* data, size_t len);
void sha256_lite_final(SHA256_CTX_LITE* ctx, uint8_t hash[32]);
