cmake_minimum_required(VERSION 3.22.1)
project(daytrace_audio LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/opus-1.6.1)
target_compile_options(opus PRIVATE -O2)
file(GLOB VAD_SOURCES CONFIGURE_DEPENDS
    vendor/webrtc/common_audio/signal_processing/*.c
    vendor/webrtc/common_audio/third_party/*.c
    vendor/webrtc/common_audio/vad/*.c)
add_library(daytrace_audio SHARED audio_jni.cpp ${VAD_SOURCES} vendor/webrtc/rtc_base/checks.cc)
target_include_directories(daytrace_audio PRIVATE vendor vendor/opus-1.6.1/include)
target_compile_definitions(daytrace_audio PRIVATE WEBRTC_POSIX)
target_compile_options(daytrace_audio PRIVATE -O2)
target_link_libraries(daytrace_audio PRIVATE opus log)
# NDK 28 defaults to flexible 16 KiB pages. Explicitly keep the JNI library aligned.
target_link_options(daytrace_audio PRIVATE "-Wl,-z,max-page-size=16384")
