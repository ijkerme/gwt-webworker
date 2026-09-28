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

import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;

/**
 * Base class used for Dedicated Workers and Shared Workers.
 * http://www.whatwg.org/specs/web-workers/current-work/
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "AbstractWorker")
public class AbstractWorker {

  protected AbstractWorker() {
    // native JsType: never instantiated from Java
  }

  /**
   * A handler that will be called if the worker encounters an error. Replaces any existing
   * handler.
   * 
   * @param handler handler to set when a worker encounters an error.
   */
  @JsOverlay
  public final void setOnError(ErrorHandler handler) {
    setOnErrorHandler(event -> HandlerDispatch.onError(handler, event));
  }

  @JsProperty(name = "onerror")
  private native void setOnErrorHandler(ErrorHandler handler);
}
