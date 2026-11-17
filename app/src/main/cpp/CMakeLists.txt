cmake_minimum_required(VERSION 3.22.1)
project("linex_engine" C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Keep existing linex_engine for backwards compatibility
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

if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_subdirectory(adrenotools)
    add_subdirectory(waylandcomp)

    # ddexec: An executable packaged as a library to run Linux binaries from app data directory
    add_library(ddexec SHARED ddexec/ddexec.c)
    target_compile_options(ddexec PRIVATE -O2 -ffreestanding -fno-builtin -fno-stack-protector -fno-asynchronous-unwind-tables)
    target_link_options(ddexec PRIVATE -nostdlib -Wl,-e,_start -Wl,-z,noexecstack -Wl,--no-undefined -Wl,-Bsymbolic
        -Wl,-z,max-page-size=16384)
else()
    # Stub for x86_64 emulator builds
    add_library(droiddeckwayland SHARED
        wayland_stub.c
    )
    target_link_libraries(droiddeckwayland ${log-lib})
endif()
