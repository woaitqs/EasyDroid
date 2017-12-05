package com.qisen.android.easydroid.permission;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;

import com.yanzhenjie.permission.AndPermission;
import com.yanzhenjie.permission.PermissionListener;
import com.yanzhenjie.permission.Rationale;
import com.yanzhenjie.permission.RationaleListener;

import java.util.List;

/**
 * @author qisen.tqs@alibaba-inc.com
 *         Created on 2017/11/29.
 */
public class AudioPermissionCheckerV21 implements PermissionChecker {

    private static final int AUDIO_REQUEST_CODE = 200;

    private final Context context;

    public AudioPermissionCheckerV21(Context context) {
        this.context = context;
    }

    @Override
    public void check(final PermissionCallback permissionCallback) {
        boolean isAllowed = AudioPermissionCheckerPre21.askForAudioPermission();

        if (isAllowed) {
            permissionCallback.onPermissionResult(PermissionUtils.PermissionType.AUDIO, true);
            return;
        }

        AndPermission.with(context)
                .permission(Manifest.permission.RECORD_AUDIO)
                .requestCode(AUDIO_REQUEST_CODE)
                .rationale(new RationaleListener() {
                    @Override
                    public void showRequestPermissionRationale(int requestCode, Rationale rationale) {
                    }
                })
                .callback(new PermissionListener() {
                    @Override
                    public void onSucceed(final int requestCode, @NonNull List<String> grantPermissions) {
                        handlePermissionResult(requestCode, permissionCallback);
                    }

                    @Override
                    public void onFailed(int requestCode, @NonNull List<String> deniedPermissions) {
                        handlePermissionResult(requestCode, permissionCallback);
                    }
                })
                .start();
    }

    private boolean hasAudioPermission() {
        String model = Build.BRAND.toLowerCase();
        if (model.contains("smart") || model.contains("vivo") || model.contains("oppo")) {
            return AudioPermissionCheckerPre21.askForAudioPermission();
        }
        return AndPermission.hasPermission(context, Manifest.permission.RECORD_AUDIO);
    }

    private void handlePermissionResult(
            final int requestCode, final PermissionCallback permissionCallback) {
        if (requestCode == AUDIO_REQUEST_CODE) {
            if (hasAudioPermission()) {
                if (permissionCallback != null) {
                    permissionCallback.onPermissionResult(PermissionUtils.PermissionType.AUDIO, true);
                }
            } else {
                if (permissionCallback != null) {
                    permissionCallback.onPermissionResult(PermissionUtils.PermissionType.AUDIO, false);
                }
            }
        }
    }
}
