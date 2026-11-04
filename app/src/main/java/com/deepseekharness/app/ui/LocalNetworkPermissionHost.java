package com.deepseekharness.app.ui;

/** Management fragments depend on this capability, not a particular navigation shell. */
public interface LocalNetworkPermissionHost {
  void requestLocalNetwork();
}
