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

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;

/**
 * Event structure returned whenever an uncaught runtime script error occurs in one of the worker's
 * scripts. See {@link ErrorHandler}
 *
 * <p>The old JSNI body of {@code getLineNumber} was {@code return this.lineno ? this.lineno : 0;}.
 * A JS {@code undefined} or {@code null} converts to {@code 0} on the way into a Java {@code int}
 * anyway, so the property mapping is equivalent.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "ErrorEvent")
public class ErrorEvent {

  protected ErrorEvent() {
    // native JsType: instances are produced by the browser, never constructed from Java
  }

  @JsProperty(name = "filename")
  public final native String getFilename();

  @JsProperty(name = "lineno")
  public final native int getLineNumber();

  @JsProperty(name = "message")
  public final native String getMessage();
}
