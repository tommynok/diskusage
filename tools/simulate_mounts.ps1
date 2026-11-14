param([string]$FilePath)

$ignoredFsTypes = @(
    "proc", "sysfs", "devpts", "cgroup", "cgroup2", "selinuxfs",
    "bpf", "tracefs", "pstore", "debugfs", "configfs", "functionfs",
    "fusectl", "binder", "vndbinder", "hwbinder", "rootfs", "rpc_pipefs",
    "ramfs"
)

function Is-ExcludedMountPoint($mountPoint, $fsType) {
    if ([string]::IsNullOrWhiteSpace($mountPoint)) { return $true }
    if ($ignoredFsTypes -contains $fsType) { return $true }
    if ($mountPoint -eq "/") { return $false }

    $exactIgnore = @("/proc", "/sys", "/dev", "/apex", "/acct", "/config",
                     "/linkerconfig", "/bootstrap-apex", "/debug_ramdisk",
                     "/data_mirror", "/mnt")
    if ($exactIgnore -contains $mountPoint) { return $true }

    $prefixIgnore = @("/proc/", "/sys/", "/dev/", "/apex/", "/acct/", "/config/",
                      "/linkerconfig/", "/bootstrap-apex/", "/debug_ramdisk/",
                      "/data_mirror/", "/mnt/installer", "/mnt/androidwritable",
                      "/mnt/user", "/mnt/pass_through", "/mnt/runtime",
                      "/mnt/asec", "/mnt/media_rw", "/data/", "/system/",
                      "/product/", "/system_ext/", "/odm/")
    foreach ($prefix in $prefixIgnore) {
        if ($mountPoint.StartsWith($prefix)) { return $true }
    }

    if ($mountPoint -eq "/storage" -or $mountPoint.StartsWith("/storage/")) { return $true }

    if ($mountPoint.StartsWith("/vendor/")) {
        if ($mountPoint -ne "/vendor/firmware_mnt" -and
            $mountPoint -ne "/vendor/dsp" -and
            $mountPoint -ne "/vendor/bt_firmware") {
            return $true
        }
    }

    $extIgnore = @(".apk", ".xml", ".so", ".conf", ".prop")
    foreach ($ext in $extIgnore) {
        if ($mountPoint.EndsWith($ext)) { return $true }
    }
    if ($mountPoint.EndsWith("/hosts")) { return $true }

    return $false
}

function Is-DefaultVisible($root) {
    return ($root -eq "/" -or $root -eq "/data" -or $root -eq "/system" -or $root -eq "/vendor" -or $root -eq "/product")
}

if (-not [string]::IsNullOrWhiteSpace($FilePath) -and (Test-Path $FilePath)) {
    $lines = Get-Content -Path $FilePath
} else {
    Write-Host "[*] Reading /proc/mounts via ADB..." -ForegroundColor Cyan
    $lines = & adb shell "cat /proc/mounts"
    if (-not $lines) {
        Write-Host "[!] Failed to get mounts via ADB." -ForegroundColor Red
        return
    }
}

$totalLines = 0
$filteredOut = @()
$realMounts = @()
$seen = @{}

foreach ($line in $lines) {
    $parts = -split $line.Trim()
    if ($parts.Count -lt 3) { continue }
    $totalLines++
    $mountPoint = $parts[1]
    $fsType = $parts[2]

    if (Is-ExcludedMountPoint $mountPoint $fsType) {
        $filteredOut += [PSCustomObject]@{ MountPoint = $mountPoint; FsType = $fsType }
    } else {
        if (-not $seen.ContainsKey($mountPoint)) {
            $seen[$mountPoint] = $true
            $realMounts += [PSCustomObject]@{ MountPoint = $mountPoint; FsType = $fsType }
        }
    }
}

$defaultVisible = $realMounts | Where-Object { Is-DefaultVisible $_.MountPoint }
$optional = $realMounts | Where-Object { -not (Is-DefaultVisible $_.MountPoint) }

Write-Host ""
Write-Host "=== DiskUsage Mount Point Filter Report ===" -ForegroundColor Green
Write-Host "Total mount points in /proc/mounts: $totalLines"
Write-Host "Filtered out as system noise: $($filteredOut.Count)"
Write-Host "Clean real partitions retained: $($realMounts.Count)"

Write-Host ""
Write-Host "[+] DEFAULT VISIBLE PARTITIONS ($($defaultVisible.Count)):" -ForegroundColor Yellow
foreach ($m in $defaultVisible) {
    Write-Host "    * $($m.MountPoint)" -ForegroundColor White
}

Write-Host ""
Write-Host "[+] OPTIONAL PARTITIONS IN 'Show/Hide Settings' ($($optional.Count)):" -ForegroundColor Cyan
foreach ($m in $optional) {
    Write-Host "    * $($m.MountPoint)" -ForegroundColor White
}

Write-Host ""
Write-Host "[+] NOISE SAMPLES FILTERED OUT (first 10):" -ForegroundColor DarkGray
for ($i = 0; $i -lt [Math]::Min(10, $filteredOut.Count); $i++) {
    Write-Host "    - $($filteredOut[$i].MountPoint) ($($filteredOut[$i].FsType))" -ForegroundColor DarkGray
}
if ($filteredOut.Count -gt 10) {
    Write-Host "    ... and $($filteredOut.Count - 10) more." -ForegroundColor DarkGray
}
