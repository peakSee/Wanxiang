cmake_minimum_required(VERSION 3.22.1)
project(wanxiang_pty C)

add_library(pty_native SHARED pty_native.c)

target_compile_options(pty_native PRIVATE -Wall -Werror=implicit-function-declaration)
