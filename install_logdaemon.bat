@echo off
setlocal EnableDelayedExpansion

:: =============================================================================
:: install_logdaemon.bat
:: Pushes LogDaemon APK to /system/priv-app/ and reboots the device.
::
:: Requirements:
::   - adb.exe on PATH (Android SDK platform-tools)
::   - Device connected via USB with USB debugging enabled
::   - Build the APK first: cd LogDaemon && gradlew :app:assembleRelease
:: =============================================================================

set APK_LOCAL=LogDaemon\app\build\outputs\apk\release\app-release.apk
set DEVICE_DIR=/system/priv-app/LogDaemon
set DEVICE_APK=%DEVICE_DIR%/LogDaemon.apk
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
    exit /b 1
)

:: --- Check adb is available --------------------------------------------------
where adb >nul 2>&1
if errorlevel 1 (
    echo [ERROR] adb not found on PATH.
    echo         Install Android SDK platform-tools and add to PATH.
    echo.
    exit /b 1
)

:: --- Wait for device ---------------------------------------------------------
echo [1/8] Waiting for device...
adb wait-for-device
if errorlevel 1 (
    echo [ERROR] No device detected.
    exit /b 1
)

:: --- Confirm device identity -------------------------------------------------
echo.
echo Device detected:
adb shell getprop ro.product.model
adb shell getprop ro.build.fingerprint
echo.

:: --- Root --------------------------------------------------------------------
echo [2/8] Requesting root...
adb root
if errorlevel 1 (
    echo [ERROR] "adb root" failed. Device may not be rooted or root is disabled.
    exit /b 1
)
:: Give adbd time to restart as root
timeout /t 2 /nobreak >nul

:: --- Remount system partition ------------------------------------------------
echo [3/8] Remounting system partition read-write...
adb remount
:: Some devices return non-zero even on a successful remount (e.g. dm-verity
:: warning). Capture the result but treat it as a warning, not a hard stop.
if errorlevel 1 (
    echo [WARN] "adb remount" returned a non-zero exit code.
    echo        If the push fails below, run:
    echo          adb disable-verity
    echo        then reboot the device and re-run this script.
)
timeout /t 1 /nobreak >nul

:: --- Create priv-app directory -----------------------------------------------
echo [4/8] Creating %DEVICE_DIR%...
adb shell mkdir -p %DEVICE_DIR%

:: --- Push APK ----------------------------------------------------------------
echo [5/8] Pushing APK...
adb push "%APK_LOCAL%" "%DEVICE_APK%"
if errorlevel 1 (
    echo [ERROR] Push failed.
    exit /b 1
)

:: --- Set ownership and permissions on APK ------------------------------------
echo [6/8] Setting ownership and permissions on APK...
adb shell chown root:root %DEVICE_APK%
adb shell chmod 644 %DEVICE_APK%
adb shell chown root:root %DEVICE_DIR%
adb shell chmod 755 %DEVICE_DIR%

:: --- Push privileged permissions whitelist -----------------------------------
echo [7/8] Pushing privileged permissions whitelist...

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
adb shell ls -la %PERMS_DEVICE%
echo.

:: --- Reboot ------------------------------------------------------------------
echo [8/8] Rebooting device...
adb reboot

echo.
echo ============================================================
echo  Done. Device is rebooting.
echo.
echo  After reboot, verify the daemon is running:
echo    adb shell ps -A ^| grep logdaemon
echo    adb logcat -s LogDaemon LogDaemon.Boot LogDaemon.Service
echo ============================================================
echo.

endlocal
