/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
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

package com.google.android.diskusage.ui;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUriExposedException;
import android.os.Handler;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.MimeTypeMap;

import com.google.android.diskusage.BuildConfig;
import com.google.android.diskusage.datasource.fast.LegacyFileImpl;
import com.google.android.diskusage.datasource.fast.StatFsSourceImpl;
import com.google.android.diskusage.filesystem.Apps2SDLoader;
import com.google.android.diskusage.filesystem.BackgroundDelete;
import com.google.android.diskusage.filesystem.mnt.MountPoint;
import com.google.android.diskusage.core.NativeScanner;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.google.android.diskusage.databinding.ActivityDiskusageBinding;
import com.google.android.diskusage.databinding.ActionBarDiskusageBinding;
import com.google.android.diskusage.databinding.DialogEntryDetailsBinding;
import com.google.android.diskusage.R;
import com.google.android.diskusage.opengl.RendererManager;
import com.google.android.diskusage.core.Scanner;
import com.google.android.diskusage.datasource.StatFsSource;
import com.google.android.diskusage.filesystem.entity.FileSystemEntry;
import com.google.android.diskusage.filesystem.entity.FileSystemEntrySmall;
import com.google.android.diskusage.filesystem.entity.FileSystemFreeSpace;
import com.google.android.diskusage.filesystem.entity.FileSystemPackage;
import com.google.android.diskusage.filesystem.entity.FileSystemRoot;
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot;
import com.google.android.diskusage.filesystem.entity.FileSystemSystemSpace;
import com.google.android.diskusage.filesystem.entity.FileSystemSpecial;
import com.google.android.diskusage.ui.common.ScanProgressDialog;
import com.google.android.diskusage.utils.Logger;

import org.jetbrains.annotations.Contract;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import splitties.toast.ToastKt;

public class DiskUsage extends LoadableActivity {

  // FIXME: wrap to direct requests to rendering thread
  public FileSystemState fileSystemState;
  public static final int RESULT_DELETE_CONFIRMED = 10;
  public static final int RESULT_DELETE_CANCELED = 11;

  public static final String STATE_KEY = "state";
  public static final String KEY_KEY = "key";

  public static final String DELETE_PATH_KEY = "path";
  public static final String DELETE_ABSOLUTE_PATH_KEY = "absolute_path";
  String key;

  private String pathToDelete;

  public DiskUsageMenu menu = DiskUsageMenu.getInstance(this);
  RendererManager rendererManager = new RendererManager(this);

  @Override
  protected void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    Logger.getLOGGER().d("DiskUsage.onCreate()");
    diskUsageBinding = ActivityDiskusageBinding.inflate(getLayoutInflater());
    setContentView(diskUsageBinding.getRoot());
    initCustomToolbar();
    android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
    if (!prefs.getBoolean("tips_fork_features_v1", false)) {
      prefs.edit().putBoolean("tips_fork_features_v1", true).apply();
      handler.postDelayed(this::showTipsDialog, 1000);
    }
    menu.onCreate();
    Intent i = getIntent();


    key = i.getStringExtra(KEY_KEY);
    if (key == null) {
      // Just close instead of crashing later
      finish();
      return;
    }
    Bundle receivedState = i.getBundleExtra(STATE_KEY);

    MountPoint mountPoint = MountPoint.getForKey(this, key);
    if (mountPoint == null) {
      finish();
      return;
    }
    Logger.getLOGGER().d("DiskUsage.onCreate(), rootPath = %s, receivedState = %s",
            mountPoint.getRoot(), receivedState);
    if (receivedState != null) onRestoreInstanceState(receivedState);
  }

  ArrayList<Runnable> afterLoadAction = new ArrayList<>();

  public void applyPatternNewRoot(FileSystemSuperRoot newRoot, String searchQuery) {
    fileSystemState.replaceRootKeepCursor(newRoot, searchQuery);
  }

  @Override
  protected void onResume() {
    super.onResume();
    rendererManager.onResume();
    if (pkg_removed != null) {
      // Check if package removed
      String pkg_name = pkg_removed.pkg;
      PackageManager pm = getPackageManager();
      try {
        pm.getPackageInfo(pkg_name, 0);
      } catch (NameNotFoundException e) {
        if (fileSystemState != null)
          fileSystemState.removeInRenderThread(pkg_removed);
      }
      pkg_removed = null;
    }
    LoadFiles(this, (root, isCached) -> {
      fileSystemState = new FileSystemState(DiskUsage.this, root);
      rendererManager.makeView(fileSystemState, root);
      fileSystemState.startZoomAnimationInRenderThread(null, !isCached, false);

      for (Runnable r : afterLoadAction) {
        r.run();
      }
      afterLoadAction.clear();
      if (pathToDelete != null) {
        String path = pathToDelete;
        pathToDelete = null;
        continueDelete(path);
      }
    }, false);
  }

  @Override
  protected void onPause() {
    rendererManager.onPause();
    super.onPause();
    if (fileSystemState != null) {
      fileSystemState.killRenderThread();
      final Bundle savedState = new Bundle();
      fileSystemState.saveState(savedState);
      afterLoadAction.add(() -> fileSystemState.restoreStateInRenderThread(savedState));
    }
  }

  @Override
  public void onActivityResult(int a, int result, Intent i) {
    super.onActivityResult(a, result, i);
    if (result != RESULT_DELETE_CONFIRMED) return;
    pathToDelete = i.getStringExtra("path");
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    return super.onCreateOptionsMenu(menu);
  }

  private static abstract class VersionedPackageViewer {
    abstract void viewPackage(String pkg);

    @NonNull
    public static VersionedPackageViewer newInstance(DiskUsage context) {
      return context.new GingerbreadPackageViewer();
    }
  }

  private final class GingerbreadPackageViewer extends VersionedPackageViewer {
    @Override
    void viewPackage(String pkg) {
      Logger.getLOGGER().d("Show package = %s", pkg);
      Intent viewIntent = new Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:" + pkg));
      startActivity(viewIntent);
    }
  }

  private final VersionedPackageViewer packageViewer =
    VersionedPackageViewer.newInstance(this);

  protected void viewPackage(@NonNull FileSystemPackage pkg) {
    packageViewer.viewPackage(pkg.pkg);
    // FIXME: reload package data instead of just removing it
    pkg_removed = pkg;
  }

  void continueDelete(String path) {
    FileSystemEntry entry = fileSystemState.masterRoot.getEntryByName(path, true);
    if (entry != null) {
      BackgroundDelete.startDelete(this, entry);
    } else {
      ToastKt.toast("Oops. Can't find directory to be deleted.");
    }
  }

  public void askForDeletion(@NonNull final FileSystemEntry entry) {
    final String path = entry.path2();
    final String fullPath = entry.absolutePath();
    Logger.getLOGGER().d("Deletion requested for %s", path);

    if (entry instanceof FileSystemEntrySmall) {
      ToastKt.toast("Delete directory instead");
      return;
    }
    if (entry instanceof FileSystemPackage) {
      return;
    }
    if (entry.children == null || entry.children.length == 0) {

      // Delete single file or directory
      new AlertDialog.Builder(this)
      .setTitle(new File(fullPath).isDirectory()
          ? getString(R.string.ask_to_delete_directory, path)
          : getString(R.string.ask_to_delete_file, path))
      .setPositiveButton(R.string.button_delete,
              (dialog, which) -> BackgroundDelete.startDelete(DiskUsage.this, entry))
      .setNegativeButton(android.R.string.cancel, null).create().show();
    } else {
      Intent i = new Intent(this, DeleteActivity.class);
      i.putExtra(DELETE_PATH_KEY, path);
      i.putExtra(DELETE_ABSOLUTE_PATH_KEY, fullPath);
      i.putExtra(DeleteActivity.NUM_FILES_KEY, entry.getNumFiles());

      i.putExtra(DiskUsage.KEY_KEY, this.key);
      i.putExtra(DeleteActivity.SIZE_KEY, entry.sizeString());
      this.startActivityForResult(i, 0);
    }
  }

  public boolean isIntentAvailable(Intent intent) {
    final PackageManager packageManager = getPackageManager();
    List<ResolveInfo> res = packageManager.queryIntentActivities(
        intent, PackageManager.MATCH_DEFAULT_ONLY);
    return res.size() > 0;
  }

  public void view(FileSystemEntry entry) {
    if (entry instanceof FileSystemEntrySmall) {
      entry = entry.parent;
    }
    if (entry instanceof FileSystemPackage) {
      viewPackage((FileSystemPackage) entry);
      return;
    }
    if (entry.parent instanceof FileSystemPackage) {
      viewPackage((FileSystemPackage) entry.parent);
      return;
    }

    String path = entry.absolutePath();
    if (path == null || path.isEmpty()) {
      ToastKt.toast(R.string.no_viewer_found);
      return;
    }

    File file = new File(path);
    if (file.isDirectory()) {
      openFolder(file, path, entry);
      return;
    }

    openFile(file, path, entry);
  }

  private Uri contentUriFor(File f) {
    if (f == null) return null;
    try {
      return FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", f);
    } catch (IllegalArgumentException | SecurityException outsideRoots) {
      Log.w("diskusage", "File outside provider roots: " + f.getAbsolutePath());
      return null;
    }
  }

  public void revealInFileManager(FileSystemEntry entry) {
    if (entry == null) return;
    String path = entry.absolutePath();
    if (path == null || path.isEmpty()) {
      ToastKt.toast(R.string.no_viewer_found);
      return;
    }
    File target = new File(path);
    File folder = target.isDirectory() ? target : target.getParentFile();
    if (folder == null) {
      folder = target;
    }

    // 1. Total Commander (supports explicit deep link with focus on target file or folder)
    try {
      Intent tcIntent = new Intent(Intent.ACTION_VIEW);
      tcIntent.setClassName("com.ghisler.android.TotalCommander", "com.ghisler.android.TotalCommander.TotalCommander");
      tcIntent.putExtra("ghisler_fileName", target.getAbsolutePath());
      tcIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(tcIntent);
      return;
    } catch (ActivityNotFoundException | SecurityException ignored) {
    }

    // 2. Folder probe via content URI
    Uri contentUri = contentUriFor(folder);
    if (contentUri != null) {
      PackageManager pm = getPackageManager();

      // Probe Material Files & standard Linux directory viewers (inode/directory)
      Intent probeInode = new Intent(Intent.ACTION_VIEW);
      probeInode.setDataAndType(contentUri, "inode/directory");
      probeInode.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      List<ResolveInfo> inodeRs = pm.queryIntentActivities(probeInode, PackageManager.MATCH_DEFAULT_ONLY);
      if (inodeRs != null && !inodeRs.isEmpty()) {
        try {
          startActivity(inodeRs.size() == 1 ? probeInode : Intent.createChooser(probeInode, null));
          return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
      }

      // Probe Solid Explorer / MiXplorer (resource/folder)
      Intent probeTc = new Intent(Intent.ACTION_VIEW);
      probeTc.setDataAndType(contentUri, "resource/folder");
      probeTc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      List<ResolveInfo> rs = pm.queryIntentActivities(probeTc, PackageManager.MATCH_DEFAULT_ONLY);
      if (rs != null && !rs.isEmpty()) {
        try {
          startActivity(rs.size() == 1 ? probeTc : Intent.createChooser(probeTc, null));
          return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
      }
    }

    // 3. Fallback: Copy path to clipboard and toast
    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (cb != null) {
      cb.setPrimaryClip(ClipData.newPlainText("path", path));
    }
    Toast.makeText(this, getString(R.string.path_copied, path), Toast.LENGTH_SHORT).show();
  }

  private void openFolder(File file, String path, FileSystemEntry entry) {
    revealInFileManager(entry);
  }

  private void openFile(File file, String path, FileSystemEntry entry) {
    Uri contentUri = contentUriFor(file);
    if (contentUri == null) {
      ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cb != null) {
        cb.setPrimaryClip(ClipData.newPlainText("path", path));
      }
      Toast.makeText(this, getString(R.string.path_copied, path), Toast.LENGTH_SHORT).show();
      return;
    }

    String fileName = (entry.name != null && !entry.name.isEmpty()) ? entry.name : file.getName();
    int dot = fileName.lastIndexOf(".");
    String mime = null;
    if (dot != -1) {
      String extension = fileName.substring(dot + 1).toLowerCase();
      mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }
    if (mime == null) {
      mime = "*/*";
    }

    try {
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.addCategory(Intent.CATEGORY_DEFAULT);
      intent.setDataAndType(contentUri, mime);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(Intent.createChooser(intent, null));
      return;
    } catch (ActivityNotFoundException | SecurityException ignored) {
      Log.e("diskusage", "Can't open viewer for file", ignored);
    }
    ToastKt.toast(R.string.no_viewer_found);
  }

  public void rescan() {
    LoadFiles(DiskUsage.this, (newRoot, isCached) -> fileSystemState.startZoomAnimationInRenderThread(newRoot, !isCached, false), true);
  }

  @Override
  public void onBackPressed() {
    finishOnBack();
  }

  public void finishOnBack() {
    if (diskUsageBinding != null && diskUsageBinding.topToolbar.searchContainer.getVisibility() == View.VISIBLE) {
      closeSearch();
      return;
    }
    if (!menu.readyToFinish()) {
      return;
    }
    Bundle outState = new Bundle();
    onSaveInstanceState(outState);
    Intent result = new Intent();
    result.putExtra(DiskUsage.STATE_KEY, outState);
    result.putExtra(DiskUsage.KEY_KEY, key);
    setResult(0, result);
    finish();
  }

  private FileSystemEntry currentSelectedEntity;
  private ActivityDiskusageBinding diskUsageBinding;

  private void initCustomToolbar() {
    if (diskUsageBinding == null) return;
    ActionBarDiskusageBinding tb = diskUsageBinding.topToolbar;

    tb.btnBack.setOnClickListener(v -> finishOnBack());

    tb.titleContainer.setOnClickListener(v -> {
      if (currentSelectedEntity != null) {
        showDetailBottomSheet(currentSelectedEntity);
      }
    });

    tb.titleContainer.setOnLongClickListener(v -> {
      if (currentSelectedEntity != null) {
        String p = currentSelectedEntity.absolutePath();
        if (p != null && !p.isEmpty()) {
          android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
          if (cb != null) {
            cb.setPrimaryClip(android.content.ClipData.newPlainText("path", p));
            Toast.makeText(DiskUsage.this, getString(R.string.path_copied, p), Toast.LENGTH_SHORT).show();
            return true;
          }
        }
      }
      return false;
    });

    tb.btnSearch.setOnClickListener(v -> openSearch());

    tb.btnOverflow.setOnClickListener(v -> showOverflowMenu());

    // Search controls
    tb.btnSearchBack.setOnClickListener(v -> closeSearch());

    tb.btnSearchClear.setOnClickListener(v -> tb.searchInput.setText(""));

    tb.searchInput.addTextChangedListener(new android.text.TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        String query = s.toString();
        tb.btnSearchClear.setVisibility(query.length() > 0 ? View.VISIBLE : View.GONE);
        menu.applyPattern(query);
      }

      @Override
      public void afterTextChanged(android.text.Editable s) {}
    });
  }

  public void openSearch() {
    if (diskUsageBinding == null) return;
    ActionBarDiskusageBinding tb = diskUsageBinding.topToolbar;
    tb.normalBarLayout.setVisibility(View.GONE);
    tb.searchContainer.setVisibility(View.VISIBLE);
    tb.searchInput.requestFocus();
    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
      imm.showSoftInput(tb.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }
  }

  public void closeSearch() {
    if (diskUsageBinding == null) return;
    ActionBarDiskusageBinding tb = diskUsageBinding.topToolbar;
    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
      imm.hideSoftInputFromWindow(tb.searchInput.getWindowToken(), 0);
    }
    tb.searchInput.setText("");
    menu.applyPattern("");
    tb.searchContainer.setVisibility(View.GONE);
    tb.normalBarLayout.setVisibility(View.VISIBLE);
  }

  public void onSearchMatchResult(boolean matched) {
    if (diskUsageBinding != null) {
      if (matched) {
        diskUsageBinding.topToolbar.searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
      } else {
        diskUsageBinding.topToolbar.searchInput.setBackgroundColor(android.graphics.Color.parseColor("#33FF0000"));
      }
    }
  }

  private void showOverflowMenu() {
    if (diskUsageBinding == null) return;
    android.widget.PopupMenu popup = new android.widget.PopupMenu(this, diskUsageBinding.topToolbar.btnOverflow);
    Menu m = popup.getMenu();
    MenuItem itemRescan = m.add(0, 1, 0, R.string.button_rescan);
    boolean isGPU = fileSystemState != null && fileSystemState.isGPU();
    MenuItem itemRenderer = m.add(0, 2, 0, isGPU ? R.string.renderer_software : R.string.renderer_hardware);
    MenuItem itemTips = m.add(0, 3, 0, R.string.menu_tips);
    MenuItem itemAbout = m.add(0, 4, 0, R.string.action_about);

    popup.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 1:
          rescan();
          return true;
        case 2:
          if (menu != null && menu.masterRoot != null) {
            rendererManager.switchRenderer(menu.masterRoot);
          }
          return true;
        case 3:
          showTipsDialog();
          return true;
        case 4:
          showAboutDialog();
          return true;
      }
      return false;
    });
    popup.show();
  }

  public void showTipsDialog() {
    Dialog dialog = new Dialog(this);
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    com.google.android.diskusage.databinding.DialogForkTipsBinding binding =
        com.google.android.diskusage.databinding.DialogForkTipsBinding.inflate(getLayoutInflater());
    dialog.setContentView(binding.getRoot());

    if (dialog.getWindow() != null) {
      dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog.getWindow().setGravity(Gravity.BOTTOM);
    }

    binding.btnTipsClose.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
  }

  public void showDetailBottomSheet(FileSystemEntry entry) {
    if (entry == null) return;
    Dialog dialog = new Dialog(this);
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    DialogEntryDetailsBinding binding = DialogEntryDetailsBinding.inflate(getLayoutInflater());
    dialog.setContentView(binding.getRoot());

    if (dialog.getWindow() != null) {
      dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog.getWindow().setGravity(Gravity.BOTTOM);
    }

    String name = (entry.name != null && !entry.name.isEmpty()) ? entry.name : entry.absolutePath();
    if (name == null || name.isEmpty()) {
      MountPoint mp = MountPoint.getForKey(this, key);
      name = getVolumeLabel(mp, entry.absolutePath());
    }
    binding.entryName.setText(name);

    String absPath = entry.absolutePath();
    binding.entryPath.setText(absPath != null && !absPath.isEmpty() ? absPath : "/");

    binding.btnCopyPath.setOnClickListener(v -> {
      if (absPath != null && !absPath.isEmpty()) {
        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
          cb.setPrimaryClip(android.content.ClipData.newPlainText("path", absPath));
          Toast.makeText(DiskUsage.this, getString(R.string.path_copied, absPath), Toast.LENGTH_SHORT).show();
        }
      }
    });

    binding.entryStats.setText(entry.toSubtitleString());

    int numFiles = entry.getNumFiles();
    if (numFiles > 0) {
      binding.entryNumFiles.setVisibility(View.VISIBLE);
      binding.entryNumFiles.setText(getString(R.string.total_files_count, numFiles));
    } else {
      binding.entryNumFiles.setVisibility(View.GONE);
    }

    boolean canOpen = !(fileSystemState == null
        || fileSystemState.masterRoot == null
        || entry == fileSystemState.masterRoot.children[0]
        || entry instanceof FileSystemSpecial
        || entry instanceof FileSystemPackage);
    binding.btnSheetOpen.setVisibility(canOpen ? View.VISIBLE : View.GONE);
    binding.btnSheetOpen.setEnabled(canOpen);
    binding.btnSheetOpen.setOnClickListener(v -> {
      dialog.dismiss();
      view(entry);
    });

    binding.btnOpenFm.setVisibility(canOpen ? View.VISIBLE : View.GONE);
    binding.btnOpenFm.setOnClickListener(v -> {
      dialog.dismiss();
      revealInFileManager(entry);
    });

    boolean fileOrNotSearching = diskUsageBinding != null
        && diskUsageBinding.topToolbar.searchContainer.getVisibility() != View.VISIBLE
        && (entry.children == null || (menu != null && menu.searchPattern == null));
    MountPoint mountPoint = MountPoint.getForKey(this, getKey());
    boolean isDeletableTarget = canOpen && entry.isDeletable()
        && mountPoint != null && mountPoint.isDeleteSupported();

    if (!isDeletableTarget) {
      binding.btnSheetDelete.setVisibility(View.GONE);
    } else {
      binding.btnSheetDelete.setVisibility(View.VISIBLE);
      binding.btnSheetDelete.setAlpha(fileOrNotSearching ? 1.0f : 0.5f);
      binding.btnSheetDelete.setOnClickListener(v -> {
        dialog.dismiss();
        if (!fileOrNotSearching) {
          Toast.makeText(DiskUsage.this, R.string.delete_folder_during_search_disabled, Toast.LENGTH_SHORT).show();
        } else {
          askForDeletion(entry);
        }
      });
    }

    binding.btnSheetClose.setOnClickListener(v -> dialog.dismiss());

    dialog.show();
  }

  public void showAboutDialog() {
    final com.google.android.diskusage.databinding.AboutDialogBinding binding =
        com.google.android.diskusage.databinding.AboutDialogBinding.inflate(getLayoutInflater(), null, false);
    binding.sourceCode.setVisibility(View.GONE);
    binding.icon.setImageBitmap(
        com.google.android.diskusage.utils.AppIconCache.getOrLoadBitmap(
            this,
            getApplicationInfo(),
            android.os.Process.myUid() / 100000,
            getResources().getDimensionPixelSize(R.dimen.default_app_icon_size)
        )
    );
    try {
      binding.versionName.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
    }
    new AlertDialog.Builder(this)
        .setView(binding.getRoot())
        .show();
  }

  public void attachGraphView(View view) {
    if (diskUsageBinding == null) {
      diskUsageBinding = ActivityDiskusageBinding.inflate(getLayoutInflater());
      setContentView(diskUsageBinding.getRoot());
      initCustomToolbar();
    }
    diskUsageBinding.graphContainer.removeAllViews();
    diskUsageBinding.graphContainer.addView(view, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    view.requestFocus();

    if (currentSelectedEntity != null) {
      updateBarInfo(currentSelectedEntity);
    }
  }

  public void updateBarInfo(FileSystemEntry position) {
    if (position == null) return;
    if (diskUsageBinding == null) {
      diskUsageBinding = ActivityDiskusageBinding.inflate(getLayoutInflater());
      setContentView(diskUsageBinding.getRoot());
      initCustomToolbar();
    }
    if (diskUsageBinding != null) {
      MountPoint mp = MountPoint.getForKey(this, key);
      String abs = position.absolutePath();
      String volumeName = getVolumeLabel(mp, abs);

      boolean isRoot = isRootEntity(position, mp);

      if (isRoot) {
        diskUsageBinding.topToolbar.barTitle.setText(volumeName);
        diskUsageBinding.topToolbar.barPathContext.setText(mp != null ? mp.getTitle() : volumeName);
        diskUsageBinding.topToolbar.barSizeCap.setText(" · " + position.toSubtitleString());
      } else {
        String name = (position.name != null && !position.name.isEmpty()) ? position.name : position.absolutePath();
        if (name == null || name.isEmpty()) name = volumeName;
        diskUsageBinding.topToolbar.barTitle.setText(name);

        String ancestors = getAncestorsBreadcrumbs(position, mp, volumeName);
        diskUsageBinding.topToolbar.barPathContext.setText(ancestors);
        diskUsageBinding.topToolbar.barSizeCap.setText(" · " + position.sizeString());
      }
    }
  }

  private boolean isRootEntity(FileSystemEntry position, MountPoint mp) {
    if (position == null) return true;
    if (fileSystemState != null && fileSystemState.masterRoot != null
        && fileSystemState.masterRoot.children != null
        && fileSystemState.masterRoot.children.length > 0) {
      if (position == fileSystemState.masterRoot.children[0]) {
        return true;
      }
    }
    String abs = position.absolutePath();
    if (mp != null && mp.getRoot() != null) {
      if (abs == null || abs.isEmpty() || abs.equals(mp.getRoot()) || (mp.getRoot() + "/").equals(abs)) {
        return true;
      }
    }
    return position.parent == null;
  }

  public String getVolumeLabel(MountPoint mp, String abs) {
    if (mp != null) {
      String root = mp.getRoot();
      if (root != null) {
        if (root.contains("emulated") || root.equals("/data")) {
          return getString(R.string.root_prefix_internal);
        }
        if (root.startsWith("/storage/")) {
          return getString(R.string.root_prefix_sd);
        }
      }
      String t = mp.getTitle();
      if (t != null && !t.isEmpty()) return t;
    }
    if (abs != null) {
      if (abs.startsWith("/storage/emulated") || abs.startsWith("/data")) {
        return getString(R.string.root_prefix_internal);
      }
      if (abs.startsWith("/storage/")) {
        return getString(R.string.root_prefix_sd);
      }
    }
    return getString(R.string.root_prefix_internal);
  }

  private String getAncestorsBreadcrumbs(FileSystemEntry entry, MountPoint mp, String volumeName) {
    if (entry == null) return volumeName;
    String abs = entry.absolutePath();
    if (abs == null || abs.isEmpty()) return volumeName;

    String root = (mp != null && mp.getRoot() != null) ? mp.getRoot() : "/storage/emulated/0";
    String sub = "";
    if (abs.startsWith(root)) {
      sub = abs.substring(root.length());
    } else if (abs.startsWith("/storage/emulated/0")) {
      sub = abs.substring("/storage/emulated/0".length());
    } else if (abs.startsWith("/storage/")) {
      int secondSlash = abs.indexOf('/', "/storage/".length());
      if (secondSlash != -1) {
        sub = abs.substring(secondSlash);
      }
    }
    if (sub.startsWith("/")) sub = sub.substring(1);

    int lastSlash = sub.lastIndexOf('/');
    if (lastSlash != -1) {
      String ancestors = sub.substring(0, lastSlash).replace("/", " \u203A ");
      return volumeName + " \u203A " + ancestors;
    } else {
      return volumeName;
    }
  }

  public void setSelectedEntity(FileSystemEntry position) {
    this.currentSelectedEntity = position;
    menu.update(position);
    if (position == null) return;
    updateBarInfo(position);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finishOnBack();
    }
      return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    if (fileSystemState == null) return;

    fileSystemState.killRenderThread();
    fileSystemState.saveState(outState);
    menu.onSaveInstanceState(outState);
  }

  @Override
  protected void onRestoreInstanceState(@NonNull final Bundle inState) {
    Logger.getLOGGER().d("DiskUsage.onRestoreInstanceState(), rootPath = %s", inState.getString(KEY_KEY));

    if (fileSystemState != null)
      fileSystemState.restoreStateInRenderThread(inState);
    else {
      afterLoadAction.add(() -> fileSystemState.restoreStateInRenderThread(inState));
    }

    menu.onRestoreInstanceState(inState);
  }

  public interface AfterLoad {
    void run(FileSystemSuperRoot root, boolean isCached);
  }

  public Handler handler = new Handler();

  static abstract class MemoryClass {
    abstract int maxHeap();

    @NonNull
    @Contract("_ -> new")
    static MemoryClass getInstance(DiskUsage diskUsage) {
      return diskUsage.new MemoryClassDetected();
    }
  }

  class MemoryClassDetected extends MemoryClass {
    @Override
    int maxHeap() {
      ActivityManager manager = (ActivityManager) DiskUsage.this.getSystemService(Context.ACTIVITY_SERVICE);
      return manager.getMemoryClass() * 1024 * 1024;
    }
  }
  MemoryClass memoryClass = MemoryClass.getInstance(this);

  private int getMemoryQuota() {
    int totalMem = memoryClass.maxHeap();
    int numMountPoints = MountPoint.getMountPoints(this).size();
    return totalMem / (numMountPoints + 1);
  }

  public static class FileSystemStats {
    final long blockSize;
    final long freeBlocks;
    final long busyBlocks;
    final long totalBlocks;

    public FileSystemStats(@NonNull MountPoint mountPoint) {
      StatFsSource stats = null;
      try {
        stats = new StatFsSourceImpl(mountPoint.getRoot());
      } catch (IllegalArgumentException e) {
        Logger.getLOGGER().e(e, "Failed to get filesystem stats for " + mountPoint.getRoot());
      }
      if (stats != null) {
        blockSize = stats.getBlockSizeLong();
        freeBlocks = stats.getAvailableBlocksLong();
        totalBlocks = stats.getBlockCountLong();
        busyBlocks = totalBlocks - freeBlocks;
      } else {
        freeBlocks = totalBlocks = busyBlocks = 0;
        blockSize = 512;
      }
    }
    public String formatUsageInfo() {
      if (totalBlocks == 0) return com.google.android.diskusage.utils.AppHelper.getAppContext().getString(R.string.mount_point_no_info);
      return String.format(com.google.android.diskusage.utils.AppHelper.getAppContext().getString(R.string.mount_point_usage),
          FileSystemEntry.calcSizeString(busyBlocks * blockSize),
          FileSystemEntry.calcSizeString(totalBlocks * blockSize));
    }
  }

  public interface ProgressGenerator {
    FileSystemEntry lastCreatedFile();
    long pos();
  }

  Runnable makeProgressUpdater(final ProgressGenerator scanner,
      final FileSystemStats stats) {
    return new Runnable() {
      private FileSystemEntry file;
      @Override
      public void run() {
        ScanProgressDialog dialog = getPersistantState().loading;
        if (dialog != null) {
          dialog.setMax(stats.busyBlocks);
          FileSystemEntry lastFile = scanner.lastCreatedFile();

          if (lastFile != file) {
            dialog.setProgress(scanner.pos(), lastFile);
          }
          file = lastFile;
        }
        handler.postDelayed(this, 50);
      }
    };
  }

  @Override
  FileSystemSuperRoot scan() throws IOException, InterruptedException {
    final MountPoint mountPoint = MountPoint.getForKey(this, key);
    final FileSystemStats stats = new FileSystemStats(mountPoint);
    int heap = getMemoryQuota();

    FileSystemEntry rootElement;
    Runnable progressUpdater;
    try {
      final NativeScanner scanner = new NativeScanner(this, stats.blockSize, stats.busyBlocks, heap);
      progressUpdater = makeProgressUpdater(scanner, stats);
      handler.post(progressUpdater);
      rootElement = scanner.scan(mountPoint);
      handler.removeCallbacks(progressUpdater);
    } catch (RuntimeException e) {
      Logger.getLOGGER().e("DiskUsage: Native scanner failed, falling back to Java scanner", e);
      final Scanner scanner = new Scanner(20, stats.blockSize, stats.busyBlocks, heap);
      progressUpdater = makeProgressUpdater(scanner, stats);
      handler.post(progressUpdater);
      rootElement = scanner.scan(LegacyFileImpl.createRoot(mountPoint.getRoot()));
      handler.removeCallbacks(progressUpdater);
    }

    ArrayList<FileSystemEntry> entries = new ArrayList<>();

    if (rootElement.children != null) {
      Collections.addAll(entries, rootElement.children);
    }

    if (mountPoint.hasApps()) {
      FileSystemRoot media = (FileSystemRoot) FileSystemRoot.makeNode(
          getString(R.string.graph_media), mountPoint.getRoot(), false).setChildren(entries.toArray(new FileSystemEntry[0]),
              stats.blockSize);
      entries = new ArrayList<>();
      entries.add(media);

      FileSystemEntry[] appList = loadApps2SD(stats.blockSize);
      if (appList != null) {
        moveAppData(appList, media, stats.blockSize);
        FileSystemEntry apps = FileSystemEntry.makeNode(null, getString(R.string.graph_apps)).setChildren(appList, stats.blockSize);
        entries.add(apps);
      }
    }

    long visibleBlocks = 0;
    for (FileSystemEntry e : entries) {
      visibleBlocks += e.getSizeInBlocks();
    }

    long systemBlocks = stats.totalBlocks - stats.freeBlocks - visibleBlocks;
    Collections.sort(entries, FileSystemEntry.COMPARE);
    if (systemBlocks > 0) {
      entries.add(new FileSystemSystemSpace(getString(R.string.graph_system_data), systemBlocks * stats.blockSize, stats.blockSize));
      entries.add(new FileSystemFreeSpace(getString(R.string.graph_free_space), stats.freeBlocks * stats.blockSize, stats.blockSize));
    } else {
      long freeBlocks = stats.freeBlocks + systemBlocks;
      if (freeBlocks > 0) {
        entries.add(new FileSystemFreeSpace(getString(R.string.graph_free_space), freeBlocks * stats.blockSize, stats.blockSize));
      }
    }

    rootElement = FileSystemRoot.makeNode(mountPoint.getTitle(), mountPoint.getRoot(), false)
        .setChildren(entries.toArray(new FileSystemEntry[0]), stats.blockSize);
    FileSystemSuperRoot newRoot = new FileSystemSuperRoot(stats.blockSize);
    newRoot.setChildren(new FileSystemEntry[] { rootElement }, stats.blockSize);
    return newRoot;
  }

  protected FileSystemEntry[] loadApps2SD(long blockSize) {
    try {
      return (new Apps2SDLoader(this).load(blockSize));
    } catch (Throwable t) {
      Logger.getLOGGER().e("DiskUsage.loadApps2SD(): Problem loading apps2sd info", t);
      return null;
    }
  }

  void moveIntoPackage(FileSystemPackage pkg,
                       @NonNull FileSystemRoot root,
                       String path, String newName,
                       FileSystemPackage.ChildType type,
                       long blockSize) {
    FileSystemEntry e = root.getByAbsolutePath(path);
    if (e != null) {
      e.remove(blockSize);
      FileSystemRoot newRoot = FileSystemRoot.makeNode(newName, path, true);
      newRoot.setChildren(e.children, blockSize);
      pkg.addPublicChild(newRoot, type, blockSize);
    }
  }

  void moveAppData(@NonNull FileSystemEntry[] apps, FileSystemRoot media, long blockSize) {
    String diskusage = "com.google.android.diskusage";
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        String cacheDir = getCacheDir().getCanonicalPath().replaceAll(diskusage, app.pkg);
        moveIntoPackage(app, media, cacheDir, "Cache", FileSystemPackage.ChildType.CACHE, blockSize);
      } catch (IOException ignored) {
      }
    }
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        String dir = getCodeCacheDir().getCanonicalPath().replaceAll(diskusage, app.pkg);
        moveIntoPackage(app, media, dir, "CodeCache", FileSystemPackage.ChildType.CACHE, blockSize);
      } catch (IOException ignored) {
      }
    }
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        String dir = getExternalCacheDir().getCanonicalPath().replaceAll(diskusage, app.pkg);
        moveIntoPackage(app, media, dir, "ExternalCache", FileSystemPackage.ChildType.CACHE, blockSize);
      } catch (IOException ignored) {
      }
    }
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        String dir = getDataDir().getCanonicalPath().replaceAll(diskusage, app.pkg);
        moveIntoPackage(app, media, dir, "Data", FileSystemPackage.ChildType.DATA, blockSize);
      } catch (IOException ignored) {
      }
    }
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        String dir = getFilesDir().getCanonicalPath().replaceAll(diskusage, app.pkg);
        moveIntoPackage(app, media, dir, "InternalFiles", FileSystemPackage.ChildType.DATA, blockSize);
      } catch (IOException ignored) {
      }
    }

    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        String dir = getExternalFilesDir(null).getCanonicalPath().replaceAll(diskusage, app.pkg);
        moveIntoPackage(app, media, dir, "Files", FileSystemPackage.ChildType.DATA, blockSize);
      } catch (IOException ignored) {
      }
    }
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        for (File mediaDir : getExternalMediaDirs()) {
          String dir = mediaDir.getCanonicalPath().replaceAll(diskusage, app.pkg);
          moveIntoPackage(app, media, dir, "MediaFiles", FileSystemPackage.ChildType.DATA, blockSize);
        }
      } catch (IOException ignored) {
      }
    }
    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      try {
        for (File mediaDir : getObbDirs()) {
          String dir = mediaDir.getCanonicalPath().replaceAll(diskusage, app.pkg);
          moveIntoPackage(app, media, dir, "Obb", FileSystemPackage.ChildType.CODE, blockSize);
        }
      } catch (IOException ignored) {
      }
    }

    for (FileSystemEntry a : apps) {
      FileSystemPackage app = (FileSystemPackage)  a;
      app.applyFilter(blockSize);
    }
    Arrays.sort(apps, FileSystemEntry.COMPARE);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    return true;
  }

  @Override
  public String getKey() {
    return key;
  }

  public void searchRequest() {
    openSearch();
  }
}
