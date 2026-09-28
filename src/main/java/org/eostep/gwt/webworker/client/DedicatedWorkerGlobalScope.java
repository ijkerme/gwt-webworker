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

import elemental2.core.JsArray;
import elemental2.core.Transferable;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

/**
 * The Dedicated worker has these methods on the top level object.
 * 
 * Runs inside the webworker, so this class cannot assume that Window or Document exists.
 * 
 * http://www.whatwg.org/specs/web-workers/current-work/
 *
 * <p>The transfer list is {@code elemental2.core.JsArray<Transferable>} - the DOM's
 * {@code sequence<Transferable>}. See {@link MessagePort} for why GWT's own {@code JsArray} could
 * not be used here.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "DedicatedWorkerGlobalScope")
public class DedicatedWorkerGlobalScope extends WorkerGlobalScope {

  protected DedicatedWorkerGlobalScope() {
    // native JsType: never instantiated from Java
  }

  /**
   * The global scope of the worker this code is running in.
   *
   * <p>The JSNI version returned {@code $self}, a variable injected by this library's linker
   * template. Reading {@code goog.global.self} gives the same object without depending on template
   * ordering - the compiler emits {@code goog.global} in every module preamble.
   */
  @JsOverlay
  public static DedicatedWorkerGlobalScope get() {
    return JsGlobal.self;
  }

  /** Sends a numeric payload. Native, so the value is never boxed into a {@code Double}. */
  public final native void postMessage(double message);

  /** Sends a string payload. A Java {@code String} is already a JS string, so no overload is needed for it. */
  public final native void postMessage(String message);

  /** Sends any structured-cloneable payload, matching the DOM's {@code any message}. */
  public final native void postMessage(Object message);

  /** Sends a numeric payload, transferring the given ports. */
  public final native void postMessage(double message, JsArray<Transferable> transfer);

  /** Sends any structured-cloneable payload, transferring the given ports. */
  public final native void postMessage(Object message, JsArray<Transferable> transfer);

  /** Java-array convenience for the transfer list; see {@link MessagePort#postMessage(double, Transferable[])}. */
  @JsOverlay
  public final void postMessage(double message, Transferable[] transfer) {
    postMessage(message, Js.<JsArray<Transferable>>uncheckedCast(transfer));
  }

  /** Java-array convenience for the transfer list. */
  @JsOverlay
  public final void postMessage(Object message, Transferable[] transfer) {
    postMessage(message, Js.<JsArray<Transferable>>uncheckedCast(transfer));
  }

  /**
   * Registers the handler for messages posted by the owning document. Wrapped like
   * {@link Worker#setOnMessage} so a throwing handler reaches the {@code
   * UncaughtExceptionHandler}.
   */
  @JsOverlay
  public final void setOnMessage(MessageHandler messageHandler) {
    setOnMessageHandler(event -> HandlerDispatch.onMessage(messageHandler, event));
  }

  @JsProperty(name = "onmessage")
  private native void setOnMessageHandler(MessageHandler messageHandler);

  public final native void terminate();
}
