package com.google.android.diskusage.utils;

import android.app.Activity;
import androidx.core.view.WindowCompat;

public class UiHelper {
    public static void setupLightStatusBar(Activity activity) {
        if (activity != null && activity.getWindow() != null) {
            WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                    .setAppearanceLightStatusBars(true);
        }
    }
}
