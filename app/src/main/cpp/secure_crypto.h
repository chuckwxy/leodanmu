#pragma once

#include <cstdint>
#include <string>
#include <vector>

std::vector<uint8_t> derive_native_key(const std::string& stage,
                                       const std::string& git_commit,
                                       const std::string& payload_raw_sha256);

std::vector<uint8_t> decode_native_bundle(const std::string& stage,
                                          const std::string& git_commit,
                                          const std::string& payload_raw_sha256,
                                          const std::vector<uint8_t>& merged);
