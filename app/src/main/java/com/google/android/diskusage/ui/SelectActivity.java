/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.google.android.diskusage.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.diskusage.R;
import com.google.android.diskusage.databinding.AboutDialogBinding;
import com.google.android.diskusage.databinding.ActivitySelectBinding;
import com.google.android.diskusage.databinding.DialogForkTipsBinding;
import com.google.android.diskusage.databinding.ItemStorageDriveBinding;
import com.google.android.diskusage.filesystem.entity.FileSystemEntry;
import com.google.android.diskusage.filesystem.mnt.MountPoint;
import com.google.android.diskusage.filesystem.mnt.RootMountPoint;
import com.google.android.diskusage.porter.PorterClient;
import com.google.android.diskusage.utils.AppIconCache;
import com.google.android.diskusage.utils.DeviceHelper;
import com.google.android.diskusage.utils.IOHelper;
import com.google.android.diskusage.utils.Logger;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

public class SelectActivity extends Activity {
  private static final String BUNDLE_KEYS = "keys";
  private static final int SHIZUKU_REQ_CODE = 4001;

  private ActivitySelectBinding binding;
  Map<String, Bundle> bundles = new TreeMap<>();

  private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
      (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQ_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
          runOnUiThread(this::updateStorageList);
        }
      };

  private final rikka.shizuku.Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener = () -> {
    new Thread(() -> {
      if (DeviceHelper.isElevated()) {
        runOnUiThread(this::updateStorageList);
      }
    }).start();
  };

  private final PorterClient.OnPermissionResultListener porterPermissionListener =
      (requestCode, granted) -> {
        runOnUiThread(this::updateStorageList);
      };

  private final PorterClient.OnBinderReceivedListener porterBinderReceivedListener = () -> {
    new Thread(() -> {
      if (DeviceHelper.isElevated()) {
        runOnUiThread(this::updateStorageList);
      }
    }).start();
  };

  private final PorterClient.OnBinderLostListener porterBinderLostListener = () -> {
    runOnUiThread(this::updateStorageList);
  };

  private abstract class AbstractUsageAction implements Runnable {
    public void runAction(String key, Class<?> viewer) {
      Intent i = new Intent(SelectActivity.this, viewer);
      i.putExtra(DiskUsage.KEY_KEY, key);
      Bundle bundle = bundles.get(key);
      if (bundle != null) {
        i.putExtra(DiskUsage.STATE_KEY, bundle);
      }
      startActivityForResult(i, 0);
    }
  }

  private class DiskUsageAction extends AbstractUsageAction {
    private final MountPoint mountPoint;

    DiskUsageAction(MountPoint mountPoint) {
      this.mountPoint = mountPoint;
    }

    public void run() {
      runAction(mountPoint.getKey(), PermissionRequestActivity.class);
    }
  }

  private class ShowHideAction implements Runnable {
    public void run() {
      Intent i = new Intent(SelectActivity.this, ShowHideMountPointsActivity.class);
      startActivity(i);
    }
  }

  public Handler handler = new Handler();
  public Runnable checkForMountsUpdates = new Runnable() {
    @Override
    public void run() {
      boolean reload = false;
      try {
        BufferedReader reader = IOHelper.getProcMountsReader();
        String line;
        int checksum = 0;
        while ((line = reader.readLine()) != null) {
          checksum += line.length();
        }
        reader.close();
        if (checksum != RootMountPoint.checksum) {
          Logger.getLOGGER().d("%s vs %s", checksum, RootMountPoint.checksum);
          reload = true;
        }
      } catch (Throwable ignored) {}

      if (reload) {
        MountPoint.reset();
        updateStorageList();
      }
      handler.postDelayed(this, 2000);
    }
  };

  public static class StorageItem {
    final String title;
    final String subtitle;
    final int iconResId;
    final Runnable action;

    StorageItem(String title, String subtitle, int iconResId, Runnable action) {
      this.title = title;
      this.subtitle = subtitle;
      this.iconResId = iconResId;
      this.action = action;
    }
  }

  private String getRootSubtitle(String root) {
    if ("/".equals(root)) return "Корневая файловая система (Root)";
    if ("/data".equals(root)) return "Пользовательские данные и приложения";
    if ("/system".equals(root)) return "Системный раздел Android";
    if ("/vendor".equals(root)) return "Драйверы и модули производителя";
    if ("/product".equals(root)) return "Компоненты прошивки (Product)";
    return "Системный раздел: " + root;
  }

  public void makeDialog() {
    updateStorageList();
  }

  public void updateStorageList() {
    if (binding == null) return;
    List<StorageItem> items = new ArrayList<>();

    // 1. Regular user mount points (Internal Storage, SD Cards)
    for (MountPoint mountPoint : MountPoint.getMountPoints(this)) {
      int icon = mountPoint.getRoot().startsWith("/storage/emulated")
          ? R.drawable.ic_drive_internal
          : R.drawable.ic_drive_sdcard;
      String subtitle = mountPoint.getRoot();
      items.add(new StorageItem(mountPoint.getTitle(), subtitle, icon, new DiskUsageAction(mountPoint)));
    }

    // 2. Root Mount Points
    boolean showRootMounts = DeviceHelper.isElevated();
    if (showRootMounts) {
      SharedPreferences prefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE);
      for (MountPoint mountPoint : RootMountPoint.getRootedMountPoints(this)) {
        if (!RootMountPoint.isMountPointVisible(prefs, mountPoint.getRoot())) continue;
        String root = mountPoint.getRoot();
        items.add(new StorageItem(root, getRootSubtitle(root), R.drawable.ic_drive_root, new DiskUsageAction(mountPoint)));
      }
      // Show/Hide option
      items.add(new StorageItem(getString(R.string.option_show_hide), getString(R.string.setup_visible_mount_points),
          R.drawable.ic_drive_settings, new ShowHideAction()));
    } else {
      items.add(new StorageItem(getString(R.string.option_root_required),
          getString(R.string.elevated_access_providers),
          R.drawable.ic_drive_shield, this::requestElevatedAccess));
    }

    ArrayAdapter<StorageItem> adapter = new ArrayAdapter<StorageItem>(this, 0, items) {
      @NonNull
      @Override
      public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ItemStorageDriveBinding itemBinding;
        if (convertView == null) {
          itemBinding = ItemStorageDriveBinding.inflate(LayoutInflater.from(getContext()), parent, false);
          convertView = itemBinding.getRoot();
          convertView.setTag(itemBinding);
        } else {
          itemBinding = (ItemStorageDriveBinding) convertView.getTag();
        }

        StorageItem item = getItem(position);
        if (item != null) {
          itemBinding.itemTitle.setText(item.title);
          itemBinding.itemSubtitle.setText(item.subtitle);
          itemBinding.itemIcon.setImageResource(item.iconResId);
          convertView.setOnClickListener(v -> item.action.run());
        }
        return convertView;
      }
    };

    binding.listStorage.setAdapter(adapter);
  }

  private void showOverflowMenu() {
    if (binding == null) return;
    PopupMenu popup = new PopupMenu(this, binding.btnOverflow);
    Menu m = popup.getMenu();
    if (DeviceHelper.isElevated()) {
      m.add(0, 4, 0, R.string.setup_visible_mount_points);
    }
    m.add(0, 1, 0, R.string.menu_shizuku);
    m.add(0, 2, 0, R.string.menu_tips);
    m.add(0, 3, 0, R.string.action_about);

    popup.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 1:
          requestShizukuAccess();
          return true;
        case 2:
          showTipsDialog();
          return true;
        case 3:
          showAboutDialog();
          return true;
        case 4:
          startActivity(new Intent(SelectActivity.this, ShowHideMountPointsActivity.class));
          return true;
      }
      return false;
    });
    popup.show();
  }

  public void requestElevatedAccess() {
    // 0. Ensure Porter binder is fetched if available
    PorterClient.fetchBinderFromManager(this);

    // 1. If Porter binder is alive, request through Porter!
    if (PorterClient.isAlive()) {
      if (PorterClient.isGranted()) {
        String mode = PorterClient.getUid() == 0 ? " (Root)" : " (Shell)";
        Toast.makeText(this, getString(R.string.shizuku_permission_already_granted) + mode, Toast.LENGTH_SHORT).show();
        updateStorageList();
        return;
      }
      boolean requested = PorterClient.requestPermission(SHIZUKU_REQ_CODE);
      if (requested) {
        return;
      }
    }

    // 2. If Shizuku binder is alive, request through Shizuku!
    try {
      if (rikka.shizuku.Shizuku.pingBinder()) {
        if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
          String mode = "";
          try {
            int uid = rikka.shizuku.Shizuku.getUid();
            mode = (uid == 0) ? " (Root)" : (uid == 2000 ? " (Shell)" : "");
          } catch (Throwable ignored) {}
          Toast.makeText(this, getString(R.string.shizuku_permission_already_granted) + mode, Toast.LENGTH_SHORT).show();
          updateStorageList();
          return;
        }
        rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQ_CODE);
        return;
      }
    } catch (Throwable ignored) {}

    // 3. Try requesting Superuser / Root rights
    new Thread(() -> {
      boolean rootGranted = DeviceHelper.requestRoot();
      runOnUiThread(() -> {
        if (rootGranted) {
          Toast.makeText(SelectActivity.this, R.string.root_permission_granted, Toast.LENGTH_SHORT).show();
          updateStorageList();
        } else {
          // 4. If no running Porter, no running Shizuku, and no root: offer to launch the installed manager app
          Intent launchIntent = null;
          if (DeviceHelper.isPorterInstalled(SelectActivity.this)) {
            launchIntent = getPackageManager().getLaunchIntentForPackage("eu.darken.porter");
          } else if (DeviceHelper.isShizukuInstalled(SelectActivity.this)) {
            launchIntent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
          }

          if (launchIntent != null) {
            startActivity(launchIntent);
            Toast.makeText(SelectActivity.this, R.string.launch_service_hint, Toast.LENGTH_LONG).show();
          } else {
            Toast.makeText(SelectActivity.this, R.string.elevation_not_available, Toast.LENGTH_LONG).show();
          }
        }
      });
    }).start();
  }

  public void requestShizukuAccess() {
    requestElevatedAccess();
  }

  public void showTipsDialog() {
    Dialog dialog = new Dialog(this);
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    DialogForkTipsBinding tipsBinding = DialogForkTipsBinding.inflate(getLayoutInflater());
    dialog.setContentView(tipsBinding.getRoot());

    if (dialog.getWindow() != null) {
      dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog.getWindow().setGravity(Gravity.BOTTOM);
    }

    tipsBinding.btnTipsClose.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
  }

  public void showAboutDialog() {
    AboutDialogBinding aboutBinding = AboutDialogBinding.inflate(getLayoutInflater(), null, false);
    aboutBinding.sourceCode.setVisibility(View.GONE);
    aboutBinding.icon.setImageBitmap(
        AppIconCache.getOrLoadBitmap(
            this,
            getApplicationInfo(),
            android.os.Process.myUid() / 100000,
            getResources().getDimensionPixelSize(R.dimen.default_app_icon_size)
        )
    );
    try {
      aboutBinding.versionName.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.action_about)
        .setView(aboutBinding.getRoot())
        .setPositiveButton(android.R.string.ok, null)
        .create()
        .show();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    try {
      PorterClient.init(this);
      PorterClient.addBinderReceivedListener(porterBinderReceivedListener);
      PorterClient.addBinderLostListener(porterBinderLostListener);
      PorterClient.addPermissionResultListener(porterPermissionListener);
      rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener);
      rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    } catch (Throwable ignored) {}
    com.google.android.diskusage.utils.UiHelper.setupLightStatusBar(this);
    FileSystemEntry.setupStrings(this);
    binding = ActivitySelectBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    binding.btnOverflow.setOnClickListener(v -> showOverflowMenu());
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    try {
      PorterClient.removeBinderReceivedListener(porterBinderReceivedListener);
      PorterClient.removeBinderLostListener(porterBinderLostListener);
      PorterClient.removePermissionResultListener(porterPermissionListener);
      rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
      rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
    } catch (Throwable ignored) {}
  }

  @Override
  protected void onResume() {
    super.onResume();
    PorterClient.fetchBinderFromManager(this);
    updateStorageList();
    handler.post(checkForMountsUpdates);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(checkForMountsUpdates);
    super.onPause();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (data == null) return;
    Bundle state = data.getBundleExtra(DiskUsage.STATE_KEY);
    String key = data.getStringExtra(DiskUsage.KEY_KEY);
    bundles.put(key, state);
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    for (Entry<String, Bundle> entry : bundles.entrySet()) {
      outState.putBundle(entry.getKey(), entry.getValue());
    }
    String[] keys = bundles.keySet().toArray(new String[0]);
    outState.putStringArray(BUNDLE_KEYS, keys);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);
    for (String key : savedInstanceState.getStringArray(BUNDLE_KEYS)) {
      bundles.put(key, savedInstanceState.getBundle(key));
    }
  }
}
