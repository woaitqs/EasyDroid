package com.qisen.android.easydroid.permission;

import android.hardware.Camera;

import java.lang.reflect.Field;

/**
 * @author qisen.tqs@alibaba-inc.com
 *         Created on 2017/11/30.
 */

public class CameraPermissionCheckerVivo implements PermissionChecker {

  private static boolean reflectCheck() {

    Camera mCamera;
    try {
      mCamera = Camera.open();
    } catch (Exception e) {
      return false;
    }

    if (mCamera == null) {
      return false;
    }

    try {
      Field field = mCamera.getClass().getDeclaredField("mHasPermission");
      field.setAccessible(true);
      return field.getBoolean(mCamera);
    } catch (Exception e) {
      // 如果在反射过程中，没有权限，则认为是有权限的.
      return true;
    } finally {
      try {
        mCamera.release();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

  }


  @Override
  public void check(PermissionCallback callback) {
    callback.onPermissionResult(PermissionUtils.PermissionType.CAMERA, reflectCheck());
  }
}