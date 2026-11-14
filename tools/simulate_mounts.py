#!/usr/bin/env python3
"""
Simulate DiskUsage mount point filtering on any Android device (Qualcomm / MediaTek / Exynos).
Usage:
    python tools/simulate_mounts.py                     # runs via adb shell
    python tools/simulate_mounts.py /path/to/mounts.txt # parses saved /proc/mounts
"""

import sys
import subprocess

IGNORED_FS_TYPES = {
    "proc", "sysfs", "devpts", "cgroup", "cgroup2", "selinuxfs",
    "bpf", "tracefs", "pstore", "debugfs", "configfs", "functionfs",
    "fusectl", "binder", "vndbinder", "hwbinder", "rootfs", "rpc_pipefs",
    "ramfs"
}

def is_excluded_mount_point(mount_point: str, fs_type: str) -> bool:
    if not mount_point:
        return True
    if fs_type in IGNORED_FS_TYPES:
        return True
    if mount_point == "/":
        return False
    if mount_point in {"/proc", "/sys", "/dev", "/apex", "/acct", "/config",
                       "/linkerconfig", "/bootstrap-apex", "/debug_ramdisk",
                       "/data_mirror", "/mnt"}:
        return True
    for prefix in ("/proc/", "/sys/", "/dev/", "/apex/", "/acct/", "/config/",
                   "/linkerconfig/", "/bootstrap-apex/", "/debug_ramdisk/",
                   "/data_mirror/", "/mnt/installer", "/mnt/androidwritable",
                   "/mnt/user", "/mnt/pass_through", "/mnt/runtime",
                   "/mnt/asec", "/mnt/media_rw", "/data/", "/system/",
                   "/product/", "/system_ext/", "/odm/"):
        if mount_point.startswith(prefix):
            return True
    if mount_point == "/storage" or mount_point.startswith("/storage/"):
        return True
    if mount_point.startswith("/vendor/"):
        if mount_point not in {"/vendor/firmware_mnt", "/vendor/dsp", "/vendor/bt_firmware"}:
            return True
    for ext in (".apk", ".xml", ".so", ".conf", ".prop"):
        if mount_point.endswith(ext):
            return True
    if mount_point.endswith("/hosts"):
        return True
    return False

def is_default_visible(root: str) -> bool:
    return root in {"/", "/data", "/system", "/vendor", "/product"}

def main():
    try:
        if len(sys.argv) > 1:
            path = sys.argv[1]
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                lines = f.readlines()
        else:
            print("[*] Подключение через ADB и чтение /proc/mounts...")
            res = subprocess.run(["adb", "shell", "cat /proc/mounts"], capture_output=True, text=True)
            if res.returncode != 0 or not res.stdout:
                print("\n[!] Ошибка: Не удалось прочитать данные через ADB.")
                print("    Проверьте, что телефон подключен по USB и включена отладка.")
                return
            lines = res.stdout.splitlines()

        total_lines = 0
        filtered_out = []
        real_mounts = []
        seen = set()

        for line in lines:
            parts = line.strip().split()
            if len(parts) < 3:
                continue
            total_lines += 1
            mount_point = parts[1]
            fs_type = parts[2]

            if is_excluded_mount_point(mount_point, fs_type):
                filtered_out.append((mount_point, fs_type))
            else:
                if mount_point not in seen:
                    seen.add(mount_point)
                    real_mounts.append((mount_point, fs_type))

        default_visible = [m for m, fs in real_mounts if is_default_visible(m)]
        optional = [m for m, fs in real_mounts if not is_default_visible(m)]

        print(f"\n=== Отчет симуляции фильтрации DiskUsage ===")
        print(f"Всего строк в /proc/mounts: {total_lines}")
        print(f"Отфильтровано как системный шум: {len(filtered_out)}")
        print(f"Реальных разделов сохранено: {len(real_mounts)}")

        print(f"\n[+] РАЗДЕЛЫ ПО УМОЛЧАНИЮ В ГЛАВНОМ МЕНЮ ({len(default_visible)}):")
        for m in default_visible:
            print(f"    * {m}")

        print(f"\n[+] ОПЦИОНАЛЬНЫЕ РАЗДЕЛЫ В 'Настройка видимых разделов' ({len(optional)}):")
        for m in optional:
            print(f"    * {m}")

        print("\n[+] ПРИМЕРЫ ОТФИЛЬТРОВАННОГО ШУМА (первые 10):")
        for m, fs in filtered_out[:10]:
            print(f"    - {m} ({fs})")
        if len(filtered_out) > 10:
            print(f"    ... и еще {len(filtered_out) - 10} записей.")
    except Exception as e:
        print(f"\n[!] Произошла ошибка: {e}")
    finally:
        # Prevent window from instantly closing when launched via double-click on Windows
        try:
            input("\nНажмите Enter, чтобы закрыть окно...")
        except Exception:
            pass

if __name__ == "__main__":
    main()
