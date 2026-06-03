cmake_minimum_required(VERSION 3.18.1)
project(logdaemon_helper C)

set(CMAKE_C_STANDARD 11)

# ── APK-packaged helper (Logv1-v3 compatibility) ──────────────────────────────
# Built as lib*.so so Gradle packages it into lib/<abi>/ and Android extracts
# it with execute permission at install time.
add_executable(logdaemon_helper logdaemon_helper.c)
set_target_properties(logdaemon_helper PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)
target_link_libraries(logdaemon_helper log)
target_compile_options(logdaemon_helper PRIVATE -Wall -Wextra -O2)

# ── Logv4: standalone init.rc daemon binary ───────────────────────────────────
# Deployed as /system/bin/logdaemon. Started by init as user=root via
# /system/etc/init/logdaemon.rc. Not packaged inside the APK — copy the
# built output from:
#   app/build/intermediates/cmake/<variant>/obj/<abi>/logdaemon
# to /system/bin/logdaemon on the target device.
add_executable(logdaemon logdaemon_helper.c)
target_link_libraries(logdaemon log)  # pthread is part of bionic on Android — no -lpthread needed
target_compile_options(logdaemon PRIVATE -Wall -Wextra -O2)
