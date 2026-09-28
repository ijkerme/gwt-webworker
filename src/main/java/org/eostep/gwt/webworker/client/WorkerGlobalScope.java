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

import com.google.gwt.core.client.JsArrayString;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;

/**
 * Represents the top level object for a Web Worker.
 * 
 * Runs inside the webworker, so this class cannot assume that Window or Document exists.
 * 
 * http://www.whatwg.org/specs/web-workers/current-work/
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "WorkerGlobalScope")
public class WorkerGlobalScope {

  protected WorkerGlobalScope() {
    // native JsType: never instantiated from Java
  }

  public final native void close();

  @JsProperty(name = "location")
  public final native WorkerLocation getLocation();

  public final native void importScript(String url);

  /**
   * Caveat!! If this array has more than one entry, importscript may not work. It should
   * eventually.
   * 
   * @param urls
   */
  public final native void importScripts(JsArrayString urls);

  @JsOverlay
  public final void importScripts(String[] urls) {
    JsArrayString jsUrls = JsArrayString.createArray().cast();
    for (int i = 0, l = urls.length; i < l; ++i) {
      jsUrls.set(i, urls[i]);
    }
    importScripts(jsUrls);
  }

  /**
   * The worker global scope itself.
   *
   * <p>The JSNI version read the JS global {@code self}; reading the {@code self} property off the
   * scope instance is the same value ({@code self.self === self}) and needs no linker template
   * variable.
   */
  @JsProperty(name = "self")
  public final native WorkerGlobalScope self();

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
