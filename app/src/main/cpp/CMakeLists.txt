cmake_minimum_required(VERSION 3.22.1)
project(coati_ocr CXX)

# Tencent ncnn prebuilt for Android with the Vulkan backend (static libraries, BSD-3-Clause).
# The package is ~33 MB (~50 MB unpacked for our two ABIs), too large to commit, so it is
# downloaded once at configure time into third_party/ncnn (git-ignored) and verified by SHA-256.
# Offline builds can point NCNN_ANDROID_VULKAN_DIR at an extracted
# ncnn-<version>-android-vulkan directory instead.
set(NCNN_VERSION 20260526)
set(NCNN_PACKAGE ncnn-${NCNN_VERSION}-android-vulkan)
set(NCNN_URL https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_PACKAGE}.zip)
set(NCNN_SHA256 26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804)
set(NCNN_ANDROID_VULKAN_DIR "" CACHE PATH "Extracted ncnn-<version>-android-vulkan directory")

if(NCNN_ANDROID_VULKAN_DIR)
    set(NCNN_PREFIX ${NCNN_ANDROID_VULKAN_DIR})
else()
    set(NCNN_CACHE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn)
    set(NCNN_PREFIX ${NCNN_CACHE}/${NCNN_PACKAGE})
    set(NCNN_ZIP ${NCNN_CACHE}/${NCNN_PACKAGE}.zip)
    file(MAKE_DIRECTORY ${NCNN_CACHE})
    # ABIs may be configured concurrently; they share the archive.
    file(LOCK ${NCNN_CACHE}/.lock GUARD FILE TIMEOUT 900)
    if(NOT EXISTS ${NCNN_PREFIX}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake)
        if(EXISTS ${NCNN_ZIP})
            file(SHA256 ${NCNN_ZIP} NCNN_ZIP_SHA256)
            if(NOT NCNN_ZIP_SHA256 STREQUAL NCNN_SHA256)
                file(REMOVE ${NCNN_ZIP})
            endif()
        endif()
        if(NOT EXISTS ${NCNN_ZIP})
            message(STATUS "Downloading ${NCNN_URL}")
            file(DOWNLOAD ${NCNN_URL} ${NCNN_ZIP}.part
                EXPECTED_HASH SHA256=${NCNN_SHA256}
                TLS_VERIFY ON
                STATUS NCNN_DOWNLOAD_STATUS)
            list(GET NCNN_DOWNLOAD_STATUS 0 NCNN_DOWNLOAD_CODE)
            if(NOT NCNN_DOWNLOAD_CODE EQUAL 0)
                file(REMOVE ${NCNN_ZIP}.part)
                message(FATAL_ERROR "ncnn download failed: ${NCNN_DOWNLOAD_STATUS}. "
                    "Download ${NCNN_URL} manually and set NCNN_ANDROID_VULKAN_DIR.")
            endif()
            file(RENAME ${NCNN_ZIP}.part ${NCNN_ZIP})
        endif()
        file(ARCHIVE_EXTRACT INPUT ${NCNN_ZIP} DESTINATION ${NCNN_CACHE}
            PATTERNS ${NCNN_PACKAGE}/${ANDROID_ABI})
    endif()
    file(LOCK ${NCNN_CACHE}/.lock RELEASE)
endif()

set(ncnn_DIR ${NCNN_PREFIX}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(coati_ocr SHARED ocr_jni.cpp ppocrv5.cpp)
target_compile_options(coati_ocr PRIVATE -O2 -Wall -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(coati_ocr PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(coati_ocr ncnn android jnigraphics log)
