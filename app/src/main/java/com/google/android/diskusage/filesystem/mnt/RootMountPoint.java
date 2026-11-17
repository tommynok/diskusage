/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.google.android.diskusage.filesystem.mnt;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.diskusage.utils.IOHelper;
import com.google.android.diskusage.utils.Logger;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RootMountPoint extends MountPoint {
  // private final String fsType;

  RootMountPoint(String root, String fsType) {
    super(root, root, false);
    // this.fsType = fsType;
  }

  @Override
  public boolean isRootRequired() {
    return true;
  }

  @Override
  public boolean hasApps() {
    return false;
  }

  @Override
  public boolean isDeleteSupported() {
    return false;
  }

  @Override
  public String getKey() {
      return "rooted:" + getRoot();
  }

  private static final Set<String> IGNORED_FS_TYPES = new HashSet<>(Arrays.asList(
      "proc", "sysfs", "devpts", "cgroup", "cgroup2", "selinuxfs",
      "bpf", "tracefs", "pstore", "debugfs", "configfs", "functionfs",
      "fusectl", "binder", "vndbinder", "hwbinder", "rootfs", "rpc_pipefs",
      "ramfs"
  ));

  public static boolean isExcludedMountPoint(String mountPoint, String fsType) {
    if (mountPoint == null || mountPoint.isEmpty()) {
      return true;
    }

    if (IGNORED_FS_TYPES.contains(fsType)) {
      return true;
    }

    // Root directory is always valid
    if (mountPoint.equals("/")) {
      return false;
    }

    // Virtual and system-internal directories
    if (mountPoint.equals("/proc") || mountPoint.startsWith("/proc/")) return true;
    if (mountPoint.equals("/sys") || mountPoint.startsWith("/sys/")) return true;
    if (mountPoint.equals("/dev") || mountPoint.startsWith("/dev/")) return true;
    if (mountPoint.equals("/apex") || mountPoint.startsWith("/apex/")) return true;
    if (mountPoint.equals("/acct") || mountPoint.startsWith("/acct/")) return true;
    if (mountPoint.equals("/config") || mountPoint.startsWith("/config/")) return true;
    if (mountPoint.equals("/linkerconfig") || mountPoint.startsWith("/linkerconfig/")) return true;
    if (mountPoint.equals("/bootstrap-apex") || mountPoint.startsWith("/bootstrap-apex/")) return true;
    if (mountPoint.equals("/debug_ramdisk") || mountPoint.startsWith("/debug_ramdisk/")) return true;
    if (mountPoint.equals("/data_mirror") || mountPoint.startsWith("/data_mirror/")) return true;

    // FUSE / Storage Manager permission views and internal mirrors
    if (mountPoint.startsWith("/mnt/installer")) return true;
    if (mountPoint.startsWith("/mnt/androidwritable")) return true;
    if (mountPoint.startsWith("/mnt/user")) return true;
    if (mountPoint.startsWith("/mnt/pass_through")) return true;
    if (mountPoint.startsWith("/mnt/runtime")) return true;
    if (mountPoint.startsWith("/mnt/asec")) return true;
    if (mountPoint.startsWith("/mnt/media_rw")) return true;
    if (mountPoint.equals("/mnt")) return true;

    // Primary and secondary storage cards (handled separately by MountPoint.getMountPoints())
    if (mountPoint.equals("/storage") || mountPoint.startsWith("/storage/")) return true;

    // Sub-mounts inside /data (user data, app sandboxes, dalvik, adb modules, profiles)
    // because scanning /data covers everything inside /data.
    if (mountPoint.startsWith("/data/")) return true;

    // Sub-mounts inside top-level partitions (e.g. /system/etc/hosts, /product/app/YouTube)
    if (mountPoint.startsWith("/system/")) return true;
    if (mountPoint.startsWith("/product/")) return true;
    if (mountPoint.startsWith("/system_ext/")) return true;
    if (mountPoint.startsWith("/odm/")) return true;

    // Under /vendor/, preserve Qualcomm firmware partitions: /vendor/firmware_mnt, /vendor/dsp, /vendor/bt_firmware
    // and ignore other arbitrary bind-mounts
    if (mountPoint.startsWith("/vendor/")) {
      if (!mountPoint.equals("/vendor/firmware_mnt")
          && !mountPoint.equals("/vendor/dsp")
          && !mountPoint.equals("/vendor/bt_firmware")) {
        return true;
      }
    }

    // Single-file bind mounts
    if (mountPoint.endsWith(".apk") || mountPoint.endsWith(".xml") || mountPoint.endsWith(".so")
        || mountPoint.endsWith(".conf") || mountPoint.endsWith(".prop")
        || mountPoint.endsWith("/hosts")) {
      return true;
    }

    return false;
  }

  public static boolean isDefaultVisible(String root) {
    return root.equals("/")
        || root.equals("/data")
        || root.equals("/system")
        || root.equals("/vendor")
        || root.equals("/product");
  }

  public static boolean isMountPointVisible(SharedPreferences prefs, String root) {
    if (prefs != null && prefs.contains("initialized")) {
      return !prefs.getBoolean(root, false);
    }
    return isDefaultVisible(root);
  }

  private static List<MountPoint> rootedMountPoints = new ArrayList<>();
  private static List<MountPoint> rawMountPoints = new ArrayList<>();
  private static Map<String, MountPoint> rootedMountPointForKey = new HashMap<>();
  private static boolean init = false;
  public static int checksum = 0;


  public static List<MountPoint> getRootedMountPoints(Context context) {
    if (context != null) {
      SharedPreferences prefs = context.getSharedPreferences("ignore_list", Context.MODE_PRIVATE);
      boolean showUnfiltered = prefs.getBoolean("show_unfiltered", false);
      return getRootedMountPoints(context, showUnfiltered);
    }
    return getRootedMountPoints(context, false);
  }

  public static List<MountPoint> getRootedMountPoints(Context context, boolean showUnfiltered) {
    initMountPoints(context);
    return showUnfiltered ? rawMountPoints : rootedMountPoints;
  }

  public static MountPoint getForKey(Context context, String key) {
    initMountPoints(context);
    return rootedMountPointForKey.get(key);
  }

  public static void initMountPoints(Context context) {
    if (init) return;
    init = true;

    try {
      checksum = 0;
      BufferedReader reader = IOHelper.getProcMountsReader();
      String line;
      while ((line = reader.readLine()) != null) {
        checksum += line.length();
        Logger.getLOGGER().d("RootMountPoint.initMountPoints(): Line: %s", line);
        String[] parts = line.split(" +");
        if (parts.length < 3) continue;
        String mountPoint = parts[1];
        Logger.getLOGGER().d("RootMountPoint.initMountPoints(): Mount point: " + mountPoint);
        String fsType = parts[2];

        if (!rootedMountPointForKey.containsKey("rooted:" + mountPoint)) {
          MountPoint m = new RootMountPoint(mountPoint, fsType);
          rawMountPoints.add(m);
          rootedMountPointForKey.put(m.getKey(), m);

          if (!isExcludedMountPoint(mountPoint, fsType)) {
            rootedMountPoints.add(m);
          }
        }
      }
      reader.close();
    } catch (Exception e) {
      Logger.getLOGGER().e("RootMountPoint.initMountPoints(): Failed to get mount points", e);
    }

    if (rootedMountPoints.isEmpty()) {
      for (String defMount : new String[]{"/", "/data", "/system"}) {
        if (!rootedMountPointForKey.containsKey("rooted:" + defMount)) {
          MountPoint m = new RootMountPoint(defMount, "ext4");
          rawMountPoints.add(m);
          rootedMountPointForKey.put(m.getKey(), m);
          rootedMountPoints.add(m);
        }
      }
    }
  }

  public static void reset() {
    rootedMountPoints = new ArrayList<>();
    rawMountPoints = new ArrayList<>();
    rootedMountPointForKey = new HashMap<>();
    init = false;
  }
}
