package com.qisen.android.easydroid.permission;

import android.hardware.Camera;

/**
 * @author qisen.tqs@alibaba-inc.com
 *         Created on 2017/11/30.
 */

public class CameraPermissionCheckerPre21 implements PermissionChecker {

  /**
   * 通过尝试打开相机的方式判断有无拍照权限（在6.0以下使用拥有root权限的管理软件可以管理权限）
   *
   * @return 是否有相机权限.
   */
  public static boolean cameraIsCanUse() {

    boolean isCanUse = true;
    Camera mCamera = null;
    try {
      mCamera = Camera.open();
      Camera.Parameters mParameters = mCamera.getParameters();
      mCamera.setParameters(mParameters);
    } catch (Exception e) {
      isCanUse = false;
    }

    if (mCamera != null) {
      try {
        mCamera.release();
      } catch (Exception e) {
        e.printStackTrace();
        return isCanUse;
      }
    }
    return isCanUse;

  }

  @Override
  public void check(PermissionCallback callback) {
    callback.onPermissionResult(PermissionUtils.PermissionType.CAMERA, cameraIsCanUse());
  }
}
