package com.google.android.diskusage.utils;

import android.app.Activity;
import android.content.res.Configuration;
import androidx.core.view.WindowCompat;

public class UiHelper {
    public static void setupLightStatusBar(Activity activity) {
        setupStatusBar(activity);
    }

    public static void setupStatusBar(Activity activity) {
        if (activity != null && activity.getWindow() != null) {
            int nightModeFlags = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean isNight = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
            WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                    .setAppearanceLightStatusBars(!isNight);
        }
    }
}
