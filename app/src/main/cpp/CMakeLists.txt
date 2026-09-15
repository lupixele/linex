cmake_minimum_required(VERSION 3.22.1)
project("linex_engine")

add_library(linex_engine SHARED
    engine_bridge.cpp
)

target_include_directories(linex_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(linex_engine
    ${log-lib}
)
