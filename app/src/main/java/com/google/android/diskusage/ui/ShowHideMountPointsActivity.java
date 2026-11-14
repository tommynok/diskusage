package com.google.android.diskusage.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuItem;
import com.google.android.diskusage.R;
import com.google.android.diskusage.filesystem.entity.FileSystemEntry;
import com.google.android.diskusage.filesystem.mnt.MountPoint;
import com.google.android.diskusage.filesystem.mnt.RootMountPoint;
import com.google.android.diskusage.ui.DiskUsage.FileSystemStats;
import java.util.List;

public class ShowHideMountPointsActivity extends PreferenceActivity {
    private static final int MENU_DESELECT_ALL = 1;
    private static final int MENU_SELECT_ALL = 2;
    private static final int MENU_ESSENTIAL_ONLY = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.google.android.diskusage.utils.UiHelper.setupLightStatusBar(this);
        addPreferencesFromResource(R.xml.mount_points_ignore_list);
        getPreferenceScreen().setOrderingAsAdded(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_DESELECT_ALL, 0, R.string.action_deselect_all)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        menu.add(0, MENU_SELECT_ALL, 1, R.string.action_select_all)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        menu.add(0, MENU_ESSENTIAL_ONLY, 2, R.string.action_essential_only)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        PreferenceScreen prefs = getPreferenceScreen();
        int count = prefs.getPreferenceCount();

        if (item.getItemId() == MENU_DESELECT_ALL) {
            for (int i = 0; i < count; i++) {
                ((CheckBoxPreference) prefs.getPreference(i)).setChecked(false);
            }
            savePreferences();
            return true;
        } else if (item.getItemId() == MENU_SELECT_ALL) {
            for (int i = 0; i < count; i++) {
                ((CheckBoxPreference) prefs.getPreference(i)).setChecked(true);
            }
            savePreferences();
            return true;
        } else if (item.getItemId() == MENU_ESSENTIAL_ONLY) {
            for (int i = 0; i < count; i++) {
                CheckBoxPreference pref = (CheckBoxPreference) prefs.getPreference(i);
                String path = pref.getTitle().toString();
                pref.setChecked(RootMountPoint.isDefaultVisible(path));
            }
            savePreferences();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<MountPoint> mountPoints = RootMountPoint.getRootedMountPoints(this);
        PreferenceScreen prefs = getPreferenceScreen();
        prefs.removeAll();
        SharedPreferences shprefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE);

        for (MountPoint mountPoint : mountPoints) {
            CheckBoxPreference pref = new CheckBoxPreference(this);
            FileSystemEntry.setupStrings(this);
            FileSystemStats stats = new FileSystemStats(mountPoint);
            pref.setSummary(stats.formatUsageInfo());
            pref.setTitle(mountPoint.getRoot());
            pref.setChecked(RootMountPoint.isMountPointVisible(shprefs, mountPoint.getRoot()));
            prefs.addPreference(pref);
        }
    }

    private void savePreferences() {
        PreferenceScreen prefs = getPreferenceScreen();
        SharedPreferences shprefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = shprefs.edit();
        editor.clear();
        editor.putBoolean("initialized", true);

        for (int i = 0; i < prefs.getPreferenceCount(); i++) {
            CheckBoxPreference pref = (CheckBoxPreference) prefs.getPreference(i);
            String root = pref.getTitle().toString();
            if (!pref.isChecked()) {
                editor.putBoolean(root, true);
            }
        }
        editor.apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePreferences();
    }
}
