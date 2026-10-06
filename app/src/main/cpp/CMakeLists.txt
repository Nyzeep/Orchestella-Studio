cmake_minimum_required(VERSION 3.22.1)
project("droidlayer_gles" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(droidlayer_gles SHARED
    native_gles_presenter.cpp
    graphics/native_gles_presenter.cpp
)

target_include_directories(droidlayer_gles PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/graphics
)

target_link_options(droidlayer_gles PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(droidlayer_gles
    android
    EGL
    GLESv3
    log
)

# Ticket 57: In-process VirGL 3D acceleration engine
add_library(virglrenderer SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/virglrenderer/src/virgl_android_gl_loader.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/virglrenderer/src/virgl_memory_quota.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/virglrenderer/src/virglrenderer.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/virglrenderer/src/virgl_jni_bridge.cpp
)

target_include_directories(virglrenderer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/virglrenderer/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/virglrenderer/include/drm
)

target_link_options(virglrenderer PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-static-libstdc++"
)

target_link_libraries(virglrenderer
    EGL
    GLESv3
    log
    m
)

