cmake_minimum_required(VERSION 3.22.1)
project("ptylauncher")

add_library(
    ptylauncher
    SHARED
    ptylauncher.c
)

target_link_libraries(
    ptylauncher
    android
    log
)
