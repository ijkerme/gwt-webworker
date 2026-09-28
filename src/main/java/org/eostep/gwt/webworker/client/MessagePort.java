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
 * The HTML 5 {@code MessagePort} API.
 *
 * <p><b>This was the last class in the library to use JSNI, and it no longer does.</b> Until 3.0.0
 * it was a {@code JavaScriptObject} overlay with hand-written JSNI bodies, because the {@code
 * postMessage} family on {@link Worker}, {@link DedicatedWorkerGlobalScope} and this class all
 * declared {@code postMessage(..., JsArray<MessagePort>)}, and GWT's own {@code
 * com.google.gwt.core.client.JsArray} is declared as {@code JsArray<T extends JavaScriptObject>}.
 * A type argument that must be a {@code JavaScriptObject} subclass cannot be satisfied by a
 * JsInterop native type - {@code @JsType(isNative = true)} may not extend {@code JavaScriptObject}
 * ("Native JsType 'X' can only extend native JsType classes") - so as long as those signatures
 * existed, MessagePort had to stay on the legacy interop model.
 *
 * <p>The way out is to stop using GWT's {@code JsArray}: {@code elemental2.core.JsArray<T>} has no
 * bound on {@code T}, and {@code elemental2.core.Transferable} is the WHATWG marker interface the
 * DOM spec actually names in {@code postMessage(message, sequence&lt;Transferable&gt;)}. With
 * those two types the constraint disappears and the class becomes a plain JsInterop native type -
 * see MIGRATION-GWT-2.13.md, section "JSNI removal".
 *
 * <p><b>Overloaded methods are legal in a native JsType.</b> Several {@code postMessage} overloads
 * here map to the single JS method name {@code postMessage}. GWT's "members must not share a
 * JavaScript name" check (JsInteropRestrictionChecker#checkInstanceNameConsistency) returns early
 * for native members, and JS dispatch is dynamic anyway.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "MessagePort")
public class MessagePort implements Transferable {

  protected MessagePort() {
    // native JsType: instances come from MessageChannel.port1/port2 or MessageEvent.getPorts()
  }

  /**
   * Registers the handler for messages delivered on this port. Replaces any existing handler.
   *
   * <p>Dispatch goes through {@link HandlerDispatch}, the same helper {@link Worker#setOnMessage}
   * and {@link DedicatedWorkerGlobalScope#setOnMessage} use, so a throwing handler is reported to
   * the registered {@code UncaughtExceptionHandler} and all four {@code setOn*} entry points
   * behave identically.
   *
   * <p><b>Defect fixed during the GWT 2.13 migration.</b> The JSNI body used to pass the
   * identifier {@code handler} to the dispatch call, but no such variable existed in that scope -
   * the parameter is named {@code messageHandler}. Every delivery therefore evaluated an
   * undefined identifier, so the registered handler was never invoked and the port was unusable.
   */
  @JsOverlay
  public final void setOnMessage(MessageHandler messageHandler) {
    setOnMessageHandler(event -> HandlerDispatch.onMessage(messageHandler, event));
  }

  @JsProperty(name = "onmessage")
  private native void setOnMessageHandler(MessageHandler messageHandler);

  public final native void close();

  public final native void start();

  /**
   * Sends a numeric payload. Kept as a native overload rather than letting the {@code Object}
   * overload box it: a {@code double} would otherwise be wrapped in a {@code java.lang.Double}
   * before it reaches the JS method.
   */
  public final native void postMessage(double message);

  /**
   * Sends a string payload.
   *
   * <p>There is deliberately no separate native overload for {@code (String, transfer)}: a Java
   * {@code String} already <em>is</em> a JS string, so it travels through the {@code Object}
   * overload unchanged. {@code double} has no such property, which is why it does get one.
   */
  public final native void postMessage(String message);

  /** Sends any structured-cloneable payload, matching the DOM's {@code any message}. */
  public final native void postMessage(Object message);

  /** Sends a numeric payload, transferring the given ports. */
  public final native void postMessage(double message, JsArray<Transferable> transfer);

  /** Sends any structured-cloneable payload, transferring the given ports. */
  public final native void postMessage(Object message, JsArray<Transferable> transfer);

  /**
   * Java-array convenience for the transfer list, mirroring elemental2-dom's own idiom.
   *
   * <p>{@code Js.uncheckedCast} is free: a GWT Java array is already a JS array, so this is a
   * compile-time reinterpretation, not a copy.
   */
  @JsOverlay
  public final void postMessage(double message, Transferable[] transfer) {
    postMessage(message, Js.<JsArray<Transferable>>uncheckedCast(transfer));
  }

  /** Java-array convenience for the transfer list. See {@link #postMessage(double, Transferable[])}. */
  @JsOverlay
  public final void postMessage(Object message, Transferable[] transfer) {
    postMessage(message, Js.<JsArray<Transferable>>uncheckedCast(transfer));
  }

  /**
   * Maps to {@code this.terminate()}.
   *
   * <p><b>Known defect, preserved deliberately.</b> The HTML spec gives {@code MessagePort} no
   * {@code terminate()} - it is a {@code Worker} member that appears to have been copied here by
   * mistake, and the browser raises {@code TypeError: this.terminate is not a function} when it is
   * called. It is left exactly as it was so that this change stays a pure JSNI-to-JsInterop
   * conversion with no behavioural drift; {@link #close()} is the correct way to release a port.
   * It is a candidate for removal in a future major version.
   */
  public final native void terminate();
}
