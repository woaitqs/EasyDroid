package com.qisen.android.easydroid.permission;

/**
 * 权限相关的回调.
 *
 * @author qisen.tqs@alibaba-inc.com
 *         Created on 2017/11/30.
 */
public interface PermissionCallback {

  /**
   * 权限是否被容许.
   *
   * @param permissionType 权限类型
   * @param granted        是否被容许.
   */
  void onPermissionResult(PermissionUtils.PermissionType permissionType, boolean granted);
}
