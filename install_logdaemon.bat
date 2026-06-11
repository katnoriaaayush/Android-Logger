@echo off
setlocal EnableDelayedExpansion

:: =============================================================================
:: install_logdaemon.bat
::
:: Compiles the native logdaemon binary with the Android NDK and installs it
:: on a rooted device via ADB.
::
:: Requirements
:: ─────────────
::   - adb.exe on PATH  (Android SDK platform-tools)
::   - Android NDK installed (Android Studio SDK Manager → SDK Tools → NDK)
::     OR set NDK_HOME to the NDK root directory
::   - Device connected via USB with USB debugging and root enabled
:: =============================================================================

set DAEMON_SRC=LogDaemon\native\logdaemon.c
set RC_SRC=LogDaemon\native\logdaemon.rc
set BINARY_OUT=%TEMP%\logdaemon
set DEVICE_BIN=/system/bin/logdaemon
set DEVICE_RC=/system/etc/init/logdaemon.rc

echo.
echo ============================================================
echo  LogDaemon Native Daemon — Install
echo ============================================================
echo.

:: --- Locate NDK clang ---------------------------------------------------------
echo [1/7] Locating NDK...

set NDK_DIR=
if not "%NDK_HOME%"=="" (
    if exist "%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" (
        set NDK_DIR=%NDK_HOME%
        goto :found_ndk
    )
)
:: Search Android Studio default NDK location — pick the latest installed version
for /d %%d in ("%LOCALAPPDATA%\Android\Sdk\ndk\*") do set NDK_DIR=%%d
if defined NDK_DIR (
    if exist "!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" goto :found_ndk
)
if exist "C:\Android\ndk" (
    for /d %%d in ("C:\Android\ndk\*") do set NDK_DIR=%%d
    if exist "!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" goto :found_ndk
)

echo [ERROR] NDK not found.
echo.
echo  Fix one of:
echo    A. Android Studio ^> SDK Manager ^> SDK Tools ^> Install NDK
echo    B. set NDK_HOME=C:\path\to\your\ndk  and re-run
echo.
pause
exit /b 1

:found_ndk
set NDK_CLANG=!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe
set NDK_SYSROOT=!NDK_DIR!\toolchains\llvm\prebuilt\windows-x86_64\sysroot
echo        NDK: !NDK_DIR!

:: --- Compile ------------------------------------------------------------------
echo [2/7] Compiling %DAEMON_SRC% for arm64-v8a...

"!NDK_CLANG!" ^
    -target aarch64-linux-android34 ^
    --sysroot="!NDK_SYSROOT!" ^
    -O2 -fPIE -pie ^
    -Wall -Wextra ^
    -o "%BINARY_OUT%" ^
    "%DAEMON_SRC%"

if errorlevel 1 (
    echo [ERROR] Compilation failed. See output above.
    pause
    exit /b 1
)
echo        Compiled: %BINARY_OUT%

:: --- Check adb / device -------------------------------------------------------
echo [3/7] Checking ADB and device...

where adb >nul 2>&1
if errorlevel 1 (
    echo [ERROR] adb not found on PATH. Install Android SDK platform-tools.
    pause
    exit /b 1
)

adb wait-for-device
echo.
echo  Device:
adb shell getprop ro.product.model
adb shell getprop ro.build.fingerprint
echo.

:: --- Root and remount ---------------------------------------------------------
echo [4/7] Requesting root and remounting system...

adb root
if errorlevel 1 (
    echo [ERROR] "adb root" failed — device may not be rooted.
    pause
    exit /b 1
)
timeout /t 2 /nobreak >nul

adb remount
if errorlevel 1 (
    echo [ERROR] "adb remount" failed.
    echo         Try:  adb disable-verity  (then reboot and re-run)
    pause
    exit /b 1
)
timeout /t 1 /nobreak >nul

:: --- Push binary --------------------------------------------------------------
echo [5/7] Pushing binary to %DEVICE_BIN%...

adb push "%BINARY_OUT%" "%DEVICE_BIN%"
if errorlevel 1 ( echo [ERROR] Push failed. & exit /b 1 )
adb shell chown root:root %DEVICE_BIN%
adb shell chmod 755 %DEVICE_BIN%

:: --- Push init.rc -------------------------------------------------------------
echo [6/7] Pushing init.rc to %DEVICE_RC%...

adb shell mkdir -p /system/etc/init
adb push "%RC_SRC%" "%DEVICE_RC%"
if errorlevel 1 ( echo [ERROR] .rc push failed. & exit /b 1 )
adb shell chown root:root %DEVICE_RC%
adb shell chmod 644 %DEVICE_RC%

:: --- Verify -------------------------------------------------------------------
echo.
echo  Files on device:
adb shell ls -la %DEVICE_BIN%
adb shell ls -la %DEVICE_RC%
echo.

:: --- Reboot -------------------------------------------------------------------
echo [7/7] Rebooting...
adb reboot

echo.
echo ============================================================
echo  Done. Device is rebooting.
echo.
echo  After reboot, verify:
echo    adb shell ps -A ^| grep logdaemon
echo    adb logcat -s logdaemon
echo ============================================================
echo.

endlocal
