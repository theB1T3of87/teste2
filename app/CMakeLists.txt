cmake_minimum_required(VERSION 3.22.1)
project(FoxGirlSupremacyNative)

set(APP_NAME FoxCubism)
set(SDK_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../CubismSdkForNative-5-r.5)
set(CORE_PATH ${SDK_ROOT_PATH}/Core)
set(FRAMEWORK_PATH ${SDK_ROOT_PATH}/Framework)
set(COMMON_SRC_PATH ${SDK_ROOT_PATH}/Samples/Common)
set(STB_PATH ${SDK_ROOT_PATH}/Samples/OpenGL/thirdParty/stb)
set(FRAMEWORK_SOURCE OpenGL)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore PROPERTIES
    IMPORTED_LOCATION ${CORE_PATH}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a
    INTERFACE_INCLUDE_DIRECTORIES ${CORE_PATH}/include
)

add_subdirectory(${FRAMEWORK_PATH} ${CMAKE_CURRENT_BINARY_DIR}/Framework)
target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)

add_library(${APP_NAME} SHARED)
add_subdirectory(${COMMON_SRC_PATH} ${CMAKE_CURRENT_BINARY_DIR}/src/Common)
add_subdirectory(src/main/cpp)
target_link_libraries(${APP_NAME} Framework Live2DCubismCore GLESv2 log android)
target_include_directories(${APP_NAME} PRIVATE ${STB_PATH})
target_link_options(${APP_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
