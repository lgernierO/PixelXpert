cmake_minimum_required(VERSION 3.28)
project(pixelxpert_zygisk CXX C ASM)

set(CMAKE_CXX_STANDARD 23)

add_subdirectory(LSPlant-jni)

# shadowhook static (exclude sh_jni.c: JNI_OnLoad would clash with ours/none)
file(GLOB SH_SRC shadowhook-cpp/*.c shadowhook-cpp/arch/${ANDROID_ABI}/*.c shadowhook-cpp/arch/${ANDROID_ABI}/*.S shadowhook-cpp/common/*.c shadowhook-cpp/third_party/xdl/*.c shadowhook-cpp/third_party/bsd/*.c shadowhook-cpp/third_party/lss/*.c)
list(FILTER SH_SRC EXCLUDE REGEX "sh_jni\\.c$")
add_library(shadowhook_static STATIC ${SH_SRC})
target_compile_features(shadowhook_static PRIVATE c_std_11)
target_include_directories(shadowhook_static PRIVATE
        shadowhook-cpp shadowhook-cpp/include
        shadowhook-cpp/arch/${ANDROID_ABI} shadowhook-cpp/common
        shadowhook-cpp/third_party/xdl shadowhook-cpp/third_party/bsd shadowhook-cpp/third_party/lss)
target_link_libraries(shadowhook_static PUBLIC log)

add_library(pixelxpert_zygisk SHARED module.cpp)
set_target_properties(pixelxpert_zygisk PROPERTIES
        CXX_SCAN_FOR_MODULES ON
        LIBRARY_OUTPUT_NAME pixelxpert_zygisk
        ANDROID_API 26)
target_include_directories(pixelxpert_zygisk PRIVATE LSPlant-jni/include)
target_link_libraries(pixelxpert_zygisk PRIVATE lsplant_static shadowhook_static log)
target_compile_options(pixelxpert_zygisk PRIVATE -Os -fdata-sections -ffunction-sections)
target_link_options(pixelxpert_zygisk PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--icf=all)
