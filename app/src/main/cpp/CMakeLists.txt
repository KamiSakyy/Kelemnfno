cmake_minimum_required(VERSION 3.22.1)
project(media_jni C)

add_library(media_jni SHARED media_jni.c)

target_compile_options(media_jni PRIVATE -O2 -fvisibility=hidden -fno-stack-protector)
target_link_options(media_jni PRIVATE "-Wl,-s" "-Wl,--exclude-libs,ALL")
