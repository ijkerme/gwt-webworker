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
 * A WorkerLocation object represents an absolute URL set at its creation.
 * 
 * Runs inside the webworker, so this class cannot assume that Window or Document exists.
 * 
 * http://www.whatwg.org/specs/web-workers/current-work/
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "WorkerLocation")
public class WorkerLocation {

  protected WorkerLocation() {
    // native JsType: instances are produced by the browser, never constructed from Java
  }

  @JsProperty(name = "hash")
  public final native String getHash();

  @JsProperty(name = "host")
  public final native String getHost();

  @JsProperty(name = "hostname")
  public final native String getHostname();

  @JsProperty(name = "href")
  public final native String getHref();

  @JsProperty(name = "pathname")
  public final native String getPathname();

  @JsProperty(name = "port")
  public final native String getPort();

  @JsProperty(name = "protocol")
  public final native String getProtocol();

  @JsProperty(name = "search")
  public final native String getSearch();
}
