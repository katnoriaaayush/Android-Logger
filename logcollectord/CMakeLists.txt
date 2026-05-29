cmake_minimum_required(VERSION 3.18.1)
project(logcollectord C)

set(CMAKE_C_STANDARD 11)

add_executable(logcollectord logcollectord.c)
target_link_libraries(logcollectord log)
target_compile_options(logcollectord PRIVATE
    -Wall -Wextra -O2 -fstack-protector-strong
)
