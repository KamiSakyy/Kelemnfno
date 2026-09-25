cmake_minimum_required(VERSION 3.22.1)
project(z C)

add_library(z SHARED zcore.c)

target_compile_options(z PRIVATE
    -O2
    -fvisibility=hidden
    -fno-stack-protector
    -fno-ident
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -fdebug-prefix-map=${CMAKE_SOURCE_DIR}=.)

target_link_options(z PRIVATE "-Wl,-s" "-Wl,--exclude-libs,ALL" "-Wl,--build-id=none")
