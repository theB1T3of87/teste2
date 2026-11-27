cmake_minimum_required(VERSION 3.22.1)
project(FoxGirlLive2D LANGUAGES C CXX)
set(APP_NAME foxgirl_live2d)
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CSM_MINIMUM_DEMO ON)
set(SDK_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../../../CubismSdkForNative-5-r.5)
set(CORE_PATH ${SDK_ROOT_PATH}/Core)
set(FRAMEWORK_PATH ${SDK_ROOT_PATH}/Framework)
set(COMMON_SRC_PATH ${SDK_ROOT_PATH}/Samples/Common)
set(STB_PATH ${SDK_ROOT_PATH}/Samples/OpenGL/thirdParty/stb)

set(CUBISM_CORE_ARCHIVE ${CORE_PATH}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a)
if(NOT EXISTS ${CUBISM_CORE_ARCHIVE})
  message(FATAL_ERROR "Cubism Core archive not found for ABI ${ANDROID_ABI}: ${CUBISM_CORE_ARCHIVE}")
endif()
if(NOT EXISTS ${STB_PATH}/stb_image.h)
  message(FATAL_ERROR "Cubism SDK stb_image.h not found: ${STB_PATH}")
endif()

add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore PROPERTIES
  IMPORTED_LOCATION ${CUBISM_CORE_ARCHIVE}
  INTERFACE_INCLUDE_DIRECTORIES ${CORE_PATH}/include)
set(RENDER_INCLUDE_PATH ${FRAMEWORK_PATH}/src/Rendering/OpenGL)
set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory(${FRAMEWORK_PATH} ${CMAKE_CURRENT_BINARY_DIR}/Framework)
target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)
add_library(${APP_NAME} SHARED)
add_subdirectory(${COMMON_SRC_PATH} ${CMAKE_CURRENT_BINARY_DIR}/Common)
target_sources(${APP_NAME} PRIVATE
  JniBridgeC.cpp LAppDefine.cpp LAppMinimumDelegate.cpp
  LAppMinimumLive2DManager.cpp LAppMinimumModel.cpp LAppPal.cpp
  LAppSprite.cpp LAppSpriteShader.cpp LAppTextureManager.cpp LAppMinimumView.cpp)
target_link_libraries(${APP_NAME} Framework Live2DCubismCore GLESv2 log android)
target_include_directories(${APP_NAME} PRIVATE ${STB_PATH})
target_link_options(${APP_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
