// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.quickactionsexample;

import android.os.Bundle;
import io.flutter.plugins.quickactions.QuickActionsPlugin;

@SuppressWarnings("deprecation")
public class EmbeddingV1Activity extends io.flutter.app.FlutterActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    QuickActionsPlugin.registerWith(
        registrarFor("io.flutter.plugins.quickactions.QuickActionsPlugin"));
  }
}
