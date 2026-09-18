cmake_minimum_required(VERSION 3.22.1)
project(kelemnfno C)

add_library(kelemnfno SHARED kelemnfno.c)

target_compile_options(kelemnfno PRIVATE -O2 -fvisibility=hidden -fno-stack-protector)
