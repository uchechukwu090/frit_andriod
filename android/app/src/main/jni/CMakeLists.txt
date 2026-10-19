cmake_minimum_required(VERSION 3.22)
project("llama_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Set GGML_CUDA to OFF for Android builds (CPU-only)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_CUDA OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

# Path to llama.cpp
add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED
    llama_jni.cpp
)

target_include_directories(llama_jni PRIVATE
    llama.cpp
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(llama_jni
    llama
    llama-common
)

# Android specific
find_library(log-lib log)
target_link_libraries(llama_jni ${log-lib})

# 16 KB page-size support (Android 15+ requirement): align our own .so.
# Third-party prebuilts (OpenCV) are handled separately; a load failure
# there is already guarded in VisionEngine (pipeline disables itself).
target_link_options(llama_jni PRIVATE "-Wl,-z,max-page-size=16384")
