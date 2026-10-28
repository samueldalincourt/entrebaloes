cmake_minimum_required(VERSION 3.22.1)
project(webtoon_llama LANGUAGES C CXX)

# Keep inference optimized even in an APK used with Flutter's debug UI.
set(CMAKE_BUILD_TYPE Release CACHE STRING "Optimized native inference" FORCE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LLAMA_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../.tools/llama.cpp" CACHE PATH "Pinned llama.cpp source")
if(NOT EXISTS "${LLAMA_SOURCE_DIR}/include/llama.h")
    message(FATAL_ERROR "Run scripts/prepare_llama.ps1 before building the Android app")
endif()
execute_process(COMMAND git -C "${LLAMA_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE LLAMA_SOURCE_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT LLAMA_SOURCE_COMMIT STREQUAL "7ecb57f7adbea2928aa468ad2aecf05b10725ad4")
    message(FATAL_ERROR "llama.cpp revision mismatch; run scripts/prepare_llama.ps1")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
# POCO MT6855 was checked on-device: all cores expose FP16 and DOTPROD.
# Keep the JNI wrapper at baseline ARM64 so it can reject unsupported devices.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "POCO CPU profile" FORCE)
add_subdirectory("${LLAMA_SOURCE_DIR}" llama-build EXCLUDE_FROM_ALL)
# AGP/NDK may clear CMAKE_*_FLAGS_RELEASE; apply optimization to the inference
# libraries themselves rather than only to the thin JNI wrapper.
foreach(inference_target llama ggml ggml-base ggml-cpu)
    if(TARGET ${inference_target})
        target_compile_options(${inference_target} PRIVATE -O3)
    endif()
endforeach()

add_library(webtoon_llama SHARED local_engine.cpp)
target_include_directories(webtoon_llama PRIVATE "${LLAMA_SOURCE_DIR}/include")
target_link_libraries(webtoon_llama PRIVATE llama log android)
target_compile_definitions(webtoon_llama PRIVATE WEBTOON_LLAMA_COMMIT="${LLAMA_SOURCE_COMMIT}" WEBTOON_ARM_DOTPROD=1)
target_compile_options(webtoon_llama PRIVATE -O3 -fvisibility=hidden)
target_link_options(webtoon_llama PRIVATE -Wl,-z,max-page-size=16384)

# Explicit developer target only; not built or packaged by the APK target list.
add_executable(webtoon_offline_bench EXCLUDE_FROM_ALL offline_benchmark.cpp)
target_include_directories(webtoon_offline_bench PRIVATE "${LLAMA_SOURCE_DIR}/include")
target_link_libraries(webtoon_offline_bench PRIVATE llama log android)
target_compile_options(webtoon_offline_bench PRIVATE -O3)
