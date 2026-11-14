@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo        DiskUsage: Mount Points Verification Tool
echo ========================================================
echo.

where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] ADB not found in PATH.
    echo Please install Android Platform Tools and add adb to PATH.
    echo.
    pause
    exit /b 1
)

echo [*] Checking connected devices via ADB...
adb devices
echo.

echo [*] Saving /proc/mounts to device_mounts.txt...
adb shell "cat /proc/mounts" > device_mounts.txt
if %errorlevel% neq 0 (
    echo [ERROR] Failed to read /proc/mounts from device!
    echo Please ensure USB debugging is enabled and authorized.
    echo.
    pause
    exit /b 1
)

echo [+] Successfully saved device_mounts.txt
echo.
echo [*] Running simulation filter...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0simulate_mounts.ps1" "%~dp0device_mounts.txt"

echo.
echo ========================================================
echo Result saved to: %~dp0device_mounts.txt
echo You can send this file to the developer.
echo ========================================================
echo.
pause
