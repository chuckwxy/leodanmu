#pragma once

#include <cstdint>
#include <string>
#include <vector>

std::vector<uint8_t> sha256_bytes(const std::vector<uint8_t>& input);
std::vector<uint8_t> zlib_decompress_bytes(const std::vector<uint8_t>& input);
std::vector<uint8_t> xor_with_key(const std::vector<uint8_t>& input, const std::vector<uint8_t>& key);
std::string bytes_to_string(const std::vector<uint8_t>& input);
std::vector<uint8_t> string_to_bytes(const std::string& input);
uint32_t read_u32_be(const uint8_t* ptr);
