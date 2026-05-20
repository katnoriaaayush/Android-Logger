cmake_minimum_required(VERSION 3.18.1)
project(logdaemon_helper C)

set(CMAKE_C_STANDARD 11)

# Build as executable but use lib*.so naming so Gradle packages it into
# the APK's lib/<abi>/ directory and Android extracts it to a path with
# execute permission at install time.
add_executable(logdaemon_helper logdaemon_helper.c)
set_target_properties(logdaemon_helper PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)
target_link_libraries(logdaemon_helper log)
target_compile_options(logdaemon_helper PRIVATE -Wall -Wextra -O2)
