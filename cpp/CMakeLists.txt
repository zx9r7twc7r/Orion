cmake_minimum_required(VERSION 3.20)
project(rtx_backend VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 查找 JNI 与 Vulkan SDK
find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)

# 包含路径设置（保留 Streamline 头文件目录）
include_directories(
        ${JNI_INCLUDE_DIRS}
        ${Vulkan_INCLUDE_DIRS}
        ${CMAKE_CURRENT_SOURCE_DIR}/deps/streamline/include
        ${CMAKE_CURRENT_SOURCE_DIR}/deps/NRD/Include
)

# NRD (NVIDIA Real-Time Denoisers) v4.18.0, built as a static library with
# embedded SPIR-V.  See cpp/deps/NRD and project_memory.md for the configure
# flags.  The library makes NO graphics API calls: it only hands back
# descriptor/SPIR-V/pool descriptions that VulkanCore.cpp turns into real
# objects.
set(NRD_LIB "${CMAKE_CURRENT_SOURCE_DIR}/deps/NRD/_Bin/Release/NRD.lib")
if(NOT EXISTS "${NRD_LIB}")
    message(FATAL_ERROR "NRD.lib not found at ${NRD_LIB}. Build cpp/deps/NRD first (NRD_STATIC_LIBRARY=ON, NRD_NRI=OFF, NRD_SUPPORTS_QUAD_INTRINSICS=OFF).")
endif()

# NRD picks the right shader permutation out of the embedded blobs through
# ShaderMake::FindPermutationInBlob, which lives in ShaderMake's ShaderBlob.cpp.
# NRD.lib does not carry it, so the host has to link ShaderMake's own blob
# library as well or every NRD object file ends up with an unresolved symbol.
set(NRD_SHADERMAKE_BLOB_LIB
    "${CMAKE_CURRENT_SOURCE_DIR}/deps/NRD/build/_deps/shadermake-build/Release/ShaderMakeBlob.lib")
if(NOT EXISTS "${NRD_SHADERMAKE_BLOB_LIB}")
    message(FATAL_ERROR "ShaderMakeBlob.lib not found at ${NRD_SHADERMAKE_BLOB_LIB}. It is produced by the NRD build.")
endif()

# 源文件
set(SOURCES
        VulkanCore.cpp
)

# 生成动态链接库
add_library(rtx_backend SHARED ${SOURCES})

# 2026-10-18 体积云离线预览器：跑 cloud_preview.comp.spv（与游戏内 v3 共用
# cloud_v3_core.glsl），不需要启动客户端就能看到形状/明暗。
# 用法见 tools/cloudpreview/preview.py。
add_executable(cloudpreview ../tools/cloudpreview/preview.cpp)
target_include_directories(cloudpreview PRIVATE ${Vulkan_INCLUDE_DIRS})
target_link_libraries(cloudpreview PRIVATE ${Vulkan_LIBRARIES})
if(MSVC)
    target_compile_options(cloudpreview PRIVATE /utf-8)
endif()

# 2026-10-18 交互式预览器：开窗口实时飞行查看（同一份 cloud_v3_core.glsl）。
add_executable(cloudviewer
    ../tools/cloudpreview/viewer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/imgui.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/imgui_draw.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/imgui_tables.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/imgui_widgets.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/backends/imgui_impl_win32.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/backends/imgui_impl_vulkan.cpp)
target_include_directories(cloudviewer PRIVATE ${Vulkan_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui
    ${CMAKE_CURRENT_SOURCE_DIR}/external/imgui/backends)
target_link_libraries(cloudviewer PRIVATE ${Vulkan_LIBRARIES})
if(MSVC)
    target_compile_options(cloudviewer PRIVATE /utf-8)
endif()

# 仅链接 Vulkan 标准库，移除对 sl.interposer.lib 的强制链接
target_link_libraries(rtx_backend PRIVATE
        ${Vulkan_LIBRARIES}
        "${NRD_LIB}"
        "${NRD_SHADERMAKE_BLOB_LIB}"
        # 2026-10-09：psapi 只为 GetProcessMemoryInfo()（[RT MEM] 诊断行的 rssMB）。
        # 排查"玩久了占满内存"时必须能把**进程工作集**和各结构占用放在同一行里看，
        # 否则无法区分"是这些结构在涨"还是"涨在别处（Java 堆 / 驱动）"。
        psapi
)

# 编译宏定义
target_compile_definitions(rtx_backend PRIVATE
        NOMINMAX
        WIN32_LEAN_AND_MEAN
        # Must match how NRD.lib was built (NRD_STATIC_LIBRARY=ON).  NRD's
        # headers use it to keep NRD_API as a plain "extern C" instead of
        # switching to dllimport.
        NRD_STATIC_LIBRARY=1
)

# VulkanCore.cpp contains UTF-8 comments and string literals. Explicitly tell
# MSVC to consume UTF-8 rather than the active Windows ANSI code page.
if(MSVC)
    target_compile_options(rtx_backend PRIVATE /utf-8)
endif()
