package com.google.android.diskusage.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.diskusage.R;
import com.google.android.diskusage.databinding.DialogPrivilegesBinding;
import com.google.android.diskusage.porter.PorterClient;
import com.google.android.diskusage.utils.DeviceHelper;

public class PrivilegesDialog {

  public static final int SHIZUKU_REQ_CODE = 4001;

  public static void show(Activity activity, Runnable onDismissCallback) {
    if (activity == null || activity.isFinishing()) return;

    // Proactively fetch Porter binder if available
    PorterClient.fetchBinderFromManager(activity);

    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    DialogPrivilegesBinding binding = DialogPrivilegesBinding.inflate(activity.getLayoutInflater());
    dialog.setContentView(binding.getRoot());

    if (dialog.getWindow() != null) {
      dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog.getWindow().setGravity(Gravity.BOTTOM);
    }

    Runnable refreshUi = () -> {
      if (activity.isFinishing()) return;
      activity.runOnUiThread(() -> updateViews(activity, binding, dialog));
    };

    // Listeners for live updates while dialog is open
    PorterClient.OnPermissionResultListener porterListener = (req, granted) -> refreshUi.run();
    PorterClient.OnBinderReceivedListener porterBinderListener = refreshUi::run;
    PorterClient.OnBinderLostListener porterLostListener = refreshUi::run;

    rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuListener =
        (req, res) -> refreshUi.run();
    rikka.shizuku.Shizuku.OnBinderReceivedListener shizukuBinderListener = refreshUi::run;
    rikka.shizuku.Shizuku.OnBinderDeadListener shizukuDeadListener = refreshUi::run;

    try {
      PorterClient.addPermissionResultListener(porterListener);
      PorterClient.addBinderReceivedListener(porterBinderListener);
      PorterClient.addBinderLostListener(porterLostListener);
      rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener);
      rikka.shizuku.Shizuku.addBinderReceivedListener(shizukuBinderListener);
      rikka.shizuku.Shizuku.addBinderDeadListener(shizukuDeadListener);
    } catch (Throwable ignored) {}

    dialog.setOnDismissListener(d -> {
      try {
        PorterClient.removePermissionResultListener(porterListener);
        PorterClient.removeBinderReceivedListener(porterBinderListener);
        PorterClient.removeBinderLostListener(porterLostListener);
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener);
        rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinderListener);
        rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuDeadListener);
      } catch (Throwable ignored) {}
      if (onDismissCallback != null) {
        onDismissCallback.run();
      }
    });

    binding.btnPrivilegesClose.setOnClickListener(v -> dialog.dismiss());

    // Root Action
    binding.btnActionRoot.setOnClickListener(v -> {
      binding.btnActionRoot.setEnabled(false);
      binding.btnActionRoot.setText(R.string.privileges_btn_checking);
      new Thread(() -> {
        boolean granted = DeviceHelper.requestRoot();
        activity.runOnUiThread(() -> {
          binding.btnActionRoot.setEnabled(true);
          updateViews(activity, binding, dialog);
          if (granted) {
            Toast.makeText(activity, R.string.root_permission_granted, Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(activity, R.string.privileges_status_not_granted, Toast.LENGTH_SHORT).show();
          }
        });
      }).start();
    });

    // Shizuku Action
    binding.btnActionShizuku.setOnClickListener(v -> {
      if (DeviceHelper.isShizukuGranted()) {
        updateViews(activity, binding, dialog);
        return;
      }
      try {
        if (rikka.shizuku.Shizuku.pingBinder()) {
          rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQ_CODE);
          return;
        }
      } catch (Throwable ignored) {}

      if (DeviceHelper.isShizukuInstalled(activity)) {
        Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launchIntent != null) {
          activity.startActivity(launchIntent);
        }
      } else {
        Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app"));
        try {
          activity.startActivity(marketIntent);
        } catch (Throwable ignored) {}
      }
    });

    // Porter Action
    binding.btnActionPorter.setOnClickListener(v -> {
      if (PorterClient.isGranted()) {
        updateViews(activity, binding, dialog);
        return;
      }
      if (PorterClient.isAlive()) {
        PorterClient.requestPermission(SHIZUKU_REQ_CODE);
        return;
      }

      if (DeviceHelper.isPorterInstalled(activity)) {
        Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage("eu.darken.porter");
        if (launchIntent != null) {
          activity.startActivity(launchIntent);
        }
      } else {
        Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/d4rken-org/porter"));
        try {
          activity.startActivity(marketIntent);
        } catch (Throwable ignored) {}
      }
    });

    // Initial render
    updateViews(activity, binding, dialog);
    dialog.show();
  }

  private static void updateViews(Activity activity, DialogPrivilegesBinding binding, Dialog dialog) {
    boolean rootGranted = DeviceHelper.isDeviceRooted();
    boolean porterGranted = PorterClient.isGranted();
    boolean shizukuGranted = DeviceHelper.isShizukuGranted();

    // 1. Top Active Mode Banner
    if (rootGranted) {
      binding.llActiveMode.setBackgroundResource(R.drawable.bg_active_mode_card);
      binding.tvActiveModeTitle.setText(R.string.privileges_active_root);
      binding.tvActiveModeDesc.setText(R.string.privileges_active_root_desc);
    } else if (porterGranted) {
      binding.llActiveMode.setBackgroundResource(R.drawable.bg_active_mode_card);
      String mode = PorterClient.getUid() == 0 ? "Root" : "Shell";
      binding.tvActiveModeTitle.setText(activity.getString(R.string.privileges_active_porter, mode));
      binding.tvActiveModeDesc.setText(R.string.privileges_active_porter_desc);
    } else if (shizukuGranted) {
      binding.llActiveMode.setBackgroundResource(R.drawable.bg_active_mode_card);
      String mode = DeviceHelper.getShizukuUid() == 0 ? "Root" : "Shell";
      binding.tvActiveModeTitle.setText(activity.getString(R.string.privileges_active_shizuku, mode));
      binding.tvActiveModeDesc.setText(R.string.privileges_active_shizuku_desc);
    } else {
      binding.llActiveMode.setBackgroundResource(R.drawable.bg_active_mode_neutral);
      binding.tvActiveModeTitle.setText(R.string.privileges_active_none);
      binding.tvActiveModeDesc.setText(R.string.privileges_active_none_desc);
    }

    // 2. Root Provider Row
    if (rootGranted) {
      binding.ivDotRoot.setImageResource(R.drawable.dot_status_green);
      binding.tvStatusRoot.setText(activity.getString(R.string.privileges_status_granted, 0));
      binding.btnActionRoot.setText(R.string.privileges_btn_check);
    } else {
      binding.ivDotRoot.setImageResource(R.drawable.dot_status_gray);
      binding.tvStatusRoot.setText(R.string.privileges_status_not_granted);
      binding.btnActionRoot.setText(R.string.privileges_btn_request);
    }

    // 3. Shizuku Provider Row
    if (shizukuGranted) {
      binding.ivDotShizuku.setImageResource(R.drawable.dot_status_green);
      binding.tvStatusShizuku.setText(activity.getString(R.string.privileges_status_granted, DeviceHelper.getShizukuUid()));
      binding.btnActionShizuku.setText(R.string.privileges_btn_check);
    } else {
      boolean shizukuAlive = false;
      try {
        shizukuAlive = rikka.shizuku.Shizuku.pingBinder();
      } catch (Throwable ignored) {}

      if (shizukuAlive) {
        binding.ivDotShizuku.setImageResource(R.drawable.dot_status_orange);
        binding.tvStatusShizuku.setText(R.string.privileges_status_service_running);
        binding.btnActionShizuku.setText(R.string.privileges_btn_request);
      } else {
        binding.ivDotShizuku.setImageResource(R.drawable.dot_status_gray);
        if (DeviceHelper.isShizukuInstalled(activity)) {
          binding.tvStatusShizuku.setText(R.string.privileges_status_not_running);
          binding.btnActionShizuku.setText(R.string.privileges_btn_open);
        } else {
          binding.tvStatusShizuku.setText(R.string.privileges_status_not_installed);
          binding.btnActionShizuku.setText(R.string.privileges_btn_install);
        }
      }
    }

    // 4. Porter Provider Row
    if (porterGranted) {
      binding.ivDotPorter.setImageResource(R.drawable.dot_status_green);
      binding.tvStatusPorter.setText(activity.getString(R.string.privileges_status_granted, DeviceHelper.getPorterUid()));
      binding.btnActionPorter.setText(R.string.privileges_btn_check);
    } else if (PorterClient.isAlive()) {
      binding.ivDotPorter.setImageResource(R.drawable.dot_status_orange);
      binding.tvStatusPorter.setText(R.string.privileges_status_service_running);
      binding.btnActionPorter.setText(R.string.privileges_btn_request);
    } else {
      binding.ivDotPorter.setImageResource(R.drawable.dot_status_gray);
      if (DeviceHelper.isPorterInstalled(activity)) {
        binding.tvStatusPorter.setText(R.string.privileges_status_not_running);
        binding.btnActionPorter.setText(R.string.privileges_btn_open);
      } else {
        binding.tvStatusPorter.setText(R.string.privileges_status_not_installed);
        binding.btnActionPorter.setText(R.string.privileges_btn_install);
      }
    }
  }
}
