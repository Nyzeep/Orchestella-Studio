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
