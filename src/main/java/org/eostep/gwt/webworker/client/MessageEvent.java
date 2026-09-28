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

import com.google.gwt.core.client.JavaScriptObject;
import elemental2.core.JsArray;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;

/**
 * General HTML 5 Message event.
 *
 * <p>Was a {@code JavaScriptObject} overlay with JSNI bodies; is now a JsInterop native type.
 * {@code getDataAsNumber} and {@code getDataAsString} intentionally read the same JS property
 * {@code data} with different Java types, exactly as the JSNI versions did - the JS value decides
 * which one is meaningful.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "MessageEvent")
public class MessageEvent {

  protected MessageEvent() {
    // native JsType: instances are produced by the browser, never constructed from Java
  }

  @JsOverlay
  public final JavaScriptObject getDataAsJSO() {
    return Json.parse(getDataAsString());
  }

  @JsProperty(name = "data")
  public final native double getDataAsNumber();

  @JsProperty(name = "data")
  public final native String getDataAsString();

  @JsProperty(name = "lastEventId")
  public final native String getLastEventId();

  @JsProperty(name = "origin")
  public final native String getOrigin();

  /**
   * The ports transferred along with this message.
   *
   * <p>The return type changed from {@code com.google.gwt.core.client.JsArray<MessagePort>} to
   * {@code elemental2.core.JsArray<MessagePort>} in 3.0.0. GWT's {@code JsArray} is declared
   * {@code JsArray<T extends JavaScriptObject>}, and {@link MessagePort} is now a JsInterop native
   * type, which cannot be a {@code JavaScriptObject} subclass - so the old type argument is no
   * longer expressible. elemental2's {@code JsArray} has no bound on {@code T}. The DOM declares
   * this member as {@code FrozenArray<MessagePort>}; the JS value is an ordinary array either way.
   */
  @JsProperty(name = "ports")
  public final native JsArray<MessagePort> getPorts();

  /**
   * The message source.
   *
   * <p><b>Known defect, preserved deliberately.</b> The DOM declares this as
   * {@code MessageEventSource?} - a {@code WindowProxy}, {@code MessagePort} or
   * {@code ServiceWorker} - not a string, so a non-null value read through this accessor is a JS
   * object being treated as a Java {@code String}. It is left as it was so that this change stays
   * a pure JSNI-to-JsInterop conversion with no behavioural drift; retyping it is a candidate for
   * a future major version.
   */
  @JsProperty(name = "source")
  public final native String getSource();
}
