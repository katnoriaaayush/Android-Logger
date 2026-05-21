@echo off
setlocal EnableDelayedExpansion

:: =============================================================================
:: install_logdaemon.bat
:: Pushes LogDaemon APK + native helper to /system/priv-app/ and reboots.
::
:: Requirements:
::   - adb.exe on PATH (Android SDK platform-tools)
::   - Device connected via USB with USB debugging enabled
::   - Build the APK first: cd LogDaemon && gradlew :app:assembleRelease
::   - Android NDK (for native helper) — found automatically in Android Studio
::     default location, or set NDK_HOME env var to override.
:: =============================================================================

set APK_LOCAL=LogDaemon\app\build\outputs\apk\release\app-release.apk
set DEVICE_DIR=/system/priv-app/LogDaemon
set DEVICE_APK=%DEVICE_DIR%/LogDaemon.apk
set DEVICE_LIB_DIR=%DEVICE_DIR%/lib/arm64
set DEVICE_HELPER=%DEVICE_LIB_DIR%/liblogdaemon_helper.so
set HELPER_SRC=LogDaemon\app\src\main\cpp\logdaemon_helper.c
set HELPER_OUT=%TEMP%\liblogdaemon_helper.so
set PERMS_SRC=LogDaemon\privapp-permissions-logdaemon.xml
set PERMS_DEVICE=/system/etc/permissions/privapp-permissions-logdaemon.xml

echo.
echo ============================================================
echo  LogDaemon Install Script
echo ============================================================
echo.

:: --- Check APK exists --------------------------------------------------------
if not exist "%APK_LOCAL%" (
    echo [ERROR] APK not found: %APK_LOCAL%
    echo         Build the project first:
    echo           cd LogDaemon
    echo           gradlew :app:assembleRelease
    echo.
    pause
    exit /b 1
)

:: --- Check adb is available --------------------------------------------------
where adb >nul 2>&1
if errorlevel 1 (
    echo [ERROR] adb not found on PATH.
    echo         Install Android SDK platform-tools and add to PATH.
    echo.
    pause
    exit /b 1
)

:: --- Locate native helper binary (pre-built from Gradle or compile now) ------
echo [0/9] Locating native helper binary...

:: Check Gradle build output first (built by assembleRelease with NDK)
set HELPER_PREBUILT=
for %%p in (
    "LogDaemon\app\build\intermediates\cmake\release\obj\arm64-v8a\liblogdaemon_helper.so"
    "LogDaemon\app\build\intermediates\cxx\Release\arm64-v8a\liblogdaemon_helper.so"
    "LogDaemon\app\build\intermediates\stripped_native_libs\release\out\lib\arm64-v8a\liblogdaemon_helper.so"
) do (
    if exist %%p (
        set HELPER_PREBUILT=%%p
        goto :found_prebuilt
    )
)
:found_prebuilt

if not "!HELPER_PREBUILT!"=="" (
    echo        Found pre-built helper from Gradle: !HELPER_PREBUILT!
    copy /Y "!HELPER_PREBUILT!" "%HELPER_OUT%" >nul
    goto :helper_ready
)

:: No Gradle build found — compile from source using NDK
echo        No Gradle build found. Compiling from source with NDK...

:: Find NDK — check NDK_HOME first, then Android Studio default locations
set NDK_DIR=
if not "%NDK_HOME%"=="" (
    if exist "%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" (
        set NDK_DIR=%NDK_HOME%
        goto :found_ndk
    )
)
for /d %%d in ("%LOCALAPPDATA%\Android\Sdk\ndk\*") do set NDK_SEARCH=%%d
if not "!NDK_SEARCH!"=="" (
    for /d %%d in ("%LOCALAPPDATA%\Android\Sdk\ndk\*") do set NDK_DIR=%%d
    if exist "!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" goto :found_ndk
)
if exist "C:\Android\ndk" (
    for /d %%d in ("C:\Android\ndk\*") do set NDK_DIR=%%d
    if exist "!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" goto :found_ndk
)

echo [ERROR] NDK not found. Cannot compile native helper.
echo.
echo  To fix this, do ONE of the following:
echo.
echo  Option A — Build the APK with NDK support (recommended):
echo    1. Open Android Studio ^> SDK Manager ^> SDK Tools ^> Install NDK
echo    2. Run: cd LogDaemon ^&^& gradlew :app:assembleRelease
echo    3. Re-run this script (it will auto-detect the built binary)
echo.
echo  Option B — Set NDK_HOME before running this script:
echo    set NDK_HOME=C:\path\to\your\ndk
echo    install_logdaemon.bat
echo.
echo  Option C — Push a pre-compiled binary manually:
echo    adb root
echo    adb remount
echo    adb shell mkdir -p /system/priv-app/LogDaemon/lib/arm64
echo    adb push liblogdaemon_helper.so /system/priv-app/LogDaemon/lib/arm64/liblogdaemon_helper.so
echo    adb shell chown root:root /system/priv-app/LogDaemon/lib/arm64/liblogdaemon_helper.so
echo    adb shell chmod 755 /system/priv-app/LogDaemon/lib/arm64/liblogdaemon_helper.so
echo.
exit /b 1

:found_ndk
echo        NDK found: !NDK_DIR!
set NDK_BIN=!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\bin
set NDK_SYSROOT=!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\sysroot
set NDK_CLANG=!NDK_BIN!\clang.exe

echo        Compiling logdaemon_helper.c for arm64-v8a...
"!NDK_CLANG!" ^
    -target aarch64-linux-android34 ^
    --sysroot="!NDK_SYSROOT!" ^
    -O2 -pie -fPIE ^
    -o "%HELPER_OUT%" ^
    "%HELPER_SRC%" ^
    -llog

if errorlevel 1 (
    echo [ERROR] Compilation failed. See output above.
    exit /b 1
)
echo        Compiled successfully: %HELPER_OUT%

:helper_ready

:: --- Wait for device ---------------------------------------------------------
echo [1/9] Waiting for device...
adb wait-for-device
if errorlevel 1 (
    echo [ERROR] No device detected.
    pause
    exit /b 1
)

:: --- Confirm device identity -------------------------------------------------
echo.
echo Device detected:
adb shell getprop ro.product.model
adb shell getprop ro.build.fingerprint
echo.

:: --- Root --------------------------------------------------------------------
echo [2/9] Requesting root...
adb root
if errorlevel 1 (
    echo [ERROR] "adb root" failed. Device may not be rooted or root is disabled.
    pause
    exit /b 1
)
timeout /t 2 /nobreak >nul

:: --- Remount system partition ------------------------------------------------
echo [3/9] Remounting system partition read-write...
adb remount
if errorlevel 1 (
    echo [ERROR] "adb remount" failed.
    echo         On some devices you may need to run:
    echo           adb disable-verity  (then reboot and re-run this script)
    pause
    exit /b 1
)
timeout /t 1 /nobreak >nul

:: --- Create priv-app directory -----------------------------------------------
echo [4/9] Creating %DEVICE_DIR%...
adb shell mkdir -p %DEVICE_DIR%
adb shell mkdir -p %DEVICE_LIB_DIR%

:: --- Push APK ----------------------------------------------------------------
echo [5/9] Pushing APK...
adb push "%APK_LOCAL%" "%DEVICE_APK%"
if errorlevel 1 (
    echo [ERROR] APK push failed.
    exit /b 1
)

:: --- Push native helper binary -----------------------------------------------
echo [6/9] Pushing native helper...
adb push "%HELPER_OUT%" "%DEVICE_HELPER%"
if errorlevel 1 (
    echo [ERROR] Native helper push failed.
    exit /b 1
)

:: --- Set ownership and permissions -------------------------------------------
echo [7/9] Setting ownership and permissions...
adb shell chown root:root %DEVICE_APK%
adb shell chmod 644 %DEVICE_APK%
adb shell chown root:root %DEVICE_DIR%
adb shell chmod 755 %DEVICE_DIR%
adb shell chown root:root %DEVICE_LIB_DIR%
adb shell chmod 755 %DEVICE_LIB_DIR%
adb shell chown root:root %DEVICE_HELPER%
adb shell chmod 755 %DEVICE_HELPER%

:: --- Push privileged permissions whitelist -----------------------------------
echo [8/9] Pushing privileged permissions whitelist...

if exist "%PERMS_SRC%" (
    adb push "%PERMS_SRC%" "%PERMS_DEVICE%"
    adb shell chown root:root %PERMS_DEVICE%
    adb shell chmod 644 %PERMS_DEVICE%
    echo        Whitelist installed at %PERMS_DEVICE%
) else (
    echo        %PERMS_SRC% not found — writing whitelist directly to device...
    adb shell "echo '<?xml version=\"1.0\" encoding=\"utf-8\"?>' > %PERMS_DEVICE%"
    adb shell "echo '<permissions>' >> %PERMS_DEVICE%"
    adb shell "echo '    <privapp-permissions package=\"com.sqa.logdaemon\">' >> %PERMS_DEVICE%"
    adb shell "echo '        <permission name=\"android.permission.READ_LOGS\"/>' >> %PERMS_DEVICE%"
    adb shell "echo '        <permission name=\"android.permission.WRITE_MEDIA_STORAGE\"/>' >> %PERMS_DEVICE%"
    adb shell "echo '        <permission name=\"android.permission.MANAGE_EXTERNAL_STORAGE\"/>' >> %PERMS_DEVICE%"
    adb shell "echo '    </privapp-permissions>' >> %PERMS_DEVICE%"
    adb shell "echo '</permissions>' >> %PERMS_DEVICE%"
    adb shell chown root:root %PERMS_DEVICE%
    adb shell chmod 644 %PERMS_DEVICE%
    echo        Whitelist written to device.
)

:: --- Verify files on device --------------------------------------------------
echo.
echo Verifying files on device:
adb shell ls -la %DEVICE_DIR%
adb shell ls -la %DEVICE_LIB_DIR%
adb shell ls -la %PERMS_DEVICE%
echo.

:: --- Reboot ------------------------------------------------------------------
echo [9/9] Rebooting device...
adb reboot

echo.
echo ============================================================
echo  Done. Device is rebooting.
echo.
echo  After reboot, verify the daemon is running:
echo    adb shell ps -A ^| grep logdaemon
echo    adb logcat -s LogDaemon LogDaemon.Boot LogDaemon.Service LogDaemon.Helper
echo ============================================================
echo.

endlocal
