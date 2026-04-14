cmake_minimum_required(VERSION 3.22.1)
project(shieldx)

add_library(
    shieldx
    SHARED
    secure_entry.cpp
    secure_crypto.cpp
    secure_bundle.cpp
    secure_guard.cpp
    secure_util.cpp
)

target_link_libraries(
    shieldx
    log
    android
)

set_target_properties(shieldx PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
