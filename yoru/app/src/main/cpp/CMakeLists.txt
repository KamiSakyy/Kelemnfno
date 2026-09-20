cmake_minimum_required(VERSION 3.22.1)
project("c++_shared")

cmake_minimum_required(VERSION 3.22.1)
project("c++_shared")

add_library(
    cxx_shared
    SHARED
    native_media.c
)

set_target_properties(
    cxx_shared
    PROPERTIES OUTPUT_NAME "c++_shared"
)

find_library(
    log-lib
    log
)

target_link_libraries(
    cxx_shared
    ${log-lib}
)
