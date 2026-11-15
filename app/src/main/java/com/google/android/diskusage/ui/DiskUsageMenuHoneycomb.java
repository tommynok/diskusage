package com.google.android.diskusage.ui;

import android.app.ActionBar;
import androidx.annotation.NonNull;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import com.google.android.diskusage.R;
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot;

public class DiskUsageMenuHoneycomb extends DiskUsageMenu {

  public DiskUsageMenuHoneycomb(DiskUsage diskusage) {
    super(diskusage);
  }
  
  @Override
  public void onCreate() {
    ActionBar actionBar = diskusage.getActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
    }
  }
  
  @Override
  public boolean readyToFinish() {
    return true;
  }
  
  @Override
  public void wrapAndSetContentView(View view, FileSystemSuperRoot newRoot) {
    super.wrapAndSetContentView(view, newRoot);
    diskusage.attachGraphView(view);
  }

  public void setShowAsAction(@NonNull MenuItem item) {
    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
  }
  
  @Override
  public boolean finishedSearch(FileSystemSuperRoot newRoot, String searchQuery) {
    boolean matched = super.finishedSearch(newRoot, searchQuery);
    diskusage.onSearchMatchResult(matched);
    return matched;
  }

  @Override
  public MenuItem makeSearchMenuEntry(@NonNull Menu menu) {
    return menu.add(R.string.button_search);
  }
  
  @Override
  public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
    return true;
  }

  @Override
  public void searchRequest() {
    diskusage.openSearch();
  }
}
