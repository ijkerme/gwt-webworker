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
import jsinterop.annotations.JsType;

/**
 * Accessor for the JavaScript global object inside a worker.
 *
 * <p>GWT's compiler always emits {@code $wnd.goog = $wnd.goog || {}; $wnd.goog.global =
 * $wnd.goog.global || $wnd;} in the module preamble, and this library's linker template sets
 * {@code $wnd = self} (see {@code DedicatedWorkerTemplate.js}), so {@code goog.global} resolves to
 * the worker global scope. Binding a static field of a native JsType to it is the JsInterop
 * equivalent of the old JSNI {@code return $self;} - and unlike {@code $self} it does not depend on
 * the template having run first.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "goog.global")
final class JsGlobal {

  private JsGlobal() {
    // native JsType: never instantiated from Java
  }

  /** The worker global scope, i.e. JS {@code self}. */
  static DedicatedWorkerGlobalScope self;
}
