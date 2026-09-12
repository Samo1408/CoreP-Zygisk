cmake_minimum_required(VERSION 3.22.1)
project(corepatch_zygisk)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(shadowhook REQUIRED CONFIG)
find_package(lsplant REQUIRED CONFIG)

add_library(corepatch_zygisk SHARED corepatch_zygisk.cpp)

target_include_directories(corepatch_zygisk PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_link_libraries(corepatch_zygisk
    shadowhook::shadowhook
    lsplant::lsplant
    log
    dl
)

# The Zygisk loader only needs this library; do not let Gradle package it as an
# ordinary application JNI library.
set_target_properties(corepatch_zygisk PROPERTIES
    OUTPUT_NAME "corepatch_zygisk"
)
