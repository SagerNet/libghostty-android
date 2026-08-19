cmake_minimum_required(VERSION 3.22.1)
project(ghostty_android CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/${ANDROID_ABI}/libghostty-vt.a)

add_library(ghostty_android SHARED ghostty_jni.cpp)

target_include_directories(ghostty_android PRIVATE
    ${GHOSTTY_VT_DIR}/include)
target_compile_definitions(ghostty_android PRIVATE GHOSTTY_STATIC)
target_compile_options(ghostty_android PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(ghostty_android PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(ghostty_android ghostty-vt log android)
