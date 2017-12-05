package com.qisen.android.easydroid.permission;

/**
 * @author qisen.tqs@alibaba-inc.com
 *         Created on 2017/11/29.
 */

public interface PermissionChecker {

  /**
   * @return 权限是否被容许.
   */
  void check(PermissionCallback callback);

}
