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
 * HTML 5 Web Worker API for Dedicated Workers.
 * http://www.whatwg.org/specs/web-workers/current-work/
 *
 * <p>The {@code postMessage} overloads all map to the same JS method name. That is legal here
 * precisely because this is a <em>native</em> JsType: GWT exempts native members from the
 * "same JavaScript name" collision check (JsInteropRestrictionChecker#checkInstanceNameConsistency
 * returns early for native members), and JS dispatch is dynamic anyway.
 *
 * <p>The transfer list is {@code elemental2.core.JsArray<Transferable>} rather than GWT's
 * {@code JsArray<MessagePort>}: the DOM declares it as {@code sequence<Transferable>}, and GWT's
 * {@code JsArray} requires a {@code JavaScriptObject} type argument, which a JsInterop native type
 * can never be. See {@link MessagePort} for the full story.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "Worker")
public class Worker extends AbstractWorker {

  /** Maps to {@code new Worker(url)}. */
  public Worker(String url) {
  }

  /** Factory kept from the JSNI version, now trivially expressible in Java. */
  @JsOverlay
  public static Worker create(String url) {
    return new Worker(url);
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
   * Registers the handler for messages posted by the worker. The handler is wrapped so that a
   * throwing handler is reported to the registered {@code UncaughtExceptionHandler} - the
   * behaviour the JSNI version had.
   */
  @JsOverlay
  public final void setOnMessage(MessageHandler messageHandler) {
    setOnMessageHandler(event -> HandlerDispatch.onMessage(messageHandler, event));
  }

  @JsProperty(name = "onmessage")
  private native void setOnMessageHandler(MessageHandler messageHandler);

  public final native void terminate();
}
