/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.eostep.gwt.webworker.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.GWT.UncaughtExceptionHandler;

/**
 * Single place where a handler invocation is turned into a JS callback that reports exceptions to
 * the registered {@link UncaughtExceptionHandler}.
 *
 * <p>Each of the four {@code setOn*} entry points used to carry its own private copy of this logic,
 * written as a JSNI method reference. Two of them had drifted (see {@link MessagePort#setOnMessage}
 * and {@link WorkerGlobalScope#setOnError}); consolidating here keeps them identical and lets the
 * JsInterop bindings stay one-liners.
 *
 * <p>Package-private on purpose: this is wiring, not API.
 */
final class HandlerDispatch {

  private HandlerDispatch() {
  }

  static void onMessage(MessageHandler messageHandler, MessageEvent event) {
    UncaughtExceptionHandler ueh = GWT.getUncaughtExceptionHandler();
    if (ueh != null) {
      try {
        messageHandler.onMessage(event);
      } catch (Exception ex) {
        ueh.onUncaughtException(ex);
      }
    } else {
      messageHandler.onMessage(event);
    }
  }

  static void onError(ErrorHandler errorHandler, ErrorEvent event) {
    UncaughtExceptionHandler ueh = GWT.getUncaughtExceptionHandler();
    if (ueh != null) {
      try {
        errorHandler.onError(event);
      } catch (Exception ex) {
        ueh.onUncaughtException(ex);
      }
    } else {
      errorHandler.onError(event);
    }
  }
}
