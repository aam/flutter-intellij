/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AppUIUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XNavigatable;
import com.intellij.xdebugger.frame.XValue;
import com.jetbrains.lang.dart.ide.runner.server.vmService.frame.DartVmServiceValue;
import io.flutter.view.InspectorPanel;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.concurrent.CompletableFuture;

public class JumpToSourceAction extends JumpToSourceActionBase {
  @Override
  protected XSourcePosition getSourcePosition(DiagnosticsNode node) {
    if (!node.hasCreationLocation()) {
      return null;
    }
    return node.getCreationLocation().getXSourcePosition();
  }

  @Override
  protected void startComputingSourcePosition(XValue value, XNavigatable navigatable) {
    // This case only typically works for Function objects where the source
    // position is available.
    value.computeSourcePosition(navigatable);
  }
}
