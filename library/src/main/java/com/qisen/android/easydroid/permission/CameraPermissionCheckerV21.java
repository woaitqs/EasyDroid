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
 *         Created on 2017/11/30.
 */

public class CameraPermissionCheckerV21 implements PermissionChecker {

    private static final int CAMERA_REQUEST_CODE = 100;

    private final Context context;

    public CameraPermissionCheckerV21(Context context) {
        this.context = context;
    }

    private boolean hasCameraPermission() {
        boolean isAllowed;
        if (Build.BRAND.toLowerCase().contains("smart") || Build.BRAND.toLowerCase().contains("oppo")) {
            isAllowed = CameraPermissionCheckerPre21.cameraIsCanUse();
        } else {
            isAllowed = AndPermission.hasPermission(context, Manifest.permission.CAMERA);
        }
        return isAllowed;
    }

    @Override
    public void check(final PermissionCallback callback) {

        boolean isAllowed = hasCameraPermission();
        if (isAllowed) {
            callback.onPermissionResult(PermissionUtils.PermissionType.CAMERA, isAllowed);
            return;
        }

        AndPermission.with(context)
                .permission(Manifest.permission.CAMERA)
                .requestCode(CAMERA_REQUEST_CODE)
                .rationale(new RationaleListener() {
                    @Override
                    public void showRequestPermissionRationale(int requestCode, Rationale rationale) {
                    }
                })
                .callback(new PermissionListener() {
                    @Override
                    public void onSucceed(final int requestCode, @NonNull List<String> grantPermissions) {
                        handlePermissionResult(CAMERA_REQUEST_CODE, callback);
                    }

                    @Override
                    public void onFailed(int requestCode, @NonNull List<String> deniedPermissions) {
                        handlePermissionResult(CAMERA_REQUEST_CODE, callback);
                    }
                })
                .start();
    }

    private void handlePermissionResult(
            final int requestCode, final PermissionCallback permissionCallback) {
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (hasCameraPermission()) {
                if (permissionCallback != null) {
                    permissionCallback.onPermissionResult(PermissionUtils.PermissionType.CAMERA, true);
                }
            } else {
                if (permissionCallback != null) {
                    permissionCallback.onPermissionResult(PermissionUtils.PermissionType.CAMERA, false);
                }
            }
        }
    }
}
