cmake_minimum_required(VERSION 3.22.1)
project("linuxdroid_engine")

add_library(linuxdroid_engine SHARED
    engine_bridge.cpp
)

target_include_directories(linuxdroid_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(linuxdroid_engine
    ${log-lib}
)
