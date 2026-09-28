/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.eostep.gwt.webworker.verify.worker;

import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.Scheduler;
import org.eostep.gwt.webworker.client.DedicatedWorkerEntryPoint;
import org.eostep.gwt.webworker.client.DedicatedWorkerGlobalScope;
import org.eostep.gwt.webworker.client.ErrorEvent;
import org.eostep.gwt.webworker.client.MessageEvent;
import org.eostep.gwt.webworker.client.MessagePort;
import org.eostep.gwt.webworker.client.Worker;
import org.eostep.gwt.webworker.client.WorkerGlobalScope;
import org.eostep.gwt.webworker.client.WorkerLocation;
import elemental2.core.JsArray;
import elemental2.core.Transferable;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/**
 * Worker half of the verification harness.
 *
 * <p>Compiled with this library's {@code dedicatedworker} linker, so it really runs inside a
 * dedicated worker. The interesting API is exercised against real browser objects -
 * {@code MessageEvent} handed to {@code onmessage}, the {@code WorkerLocation} from
 * {@code getLocation()}, the {@code ErrorEvent} delivered to {@code onerror} - rather than only
 * being referenced. See {@link #touchEverything()} for the compile-time reachability anchor.
 */
public class WorkerSmoke extends DedicatedWorkerEntryPoint {

  private static final String PREFIX = "worker:";

  /**
   * Receivers for the compile-time anchor are static fields on purpose: locals initialised to
   * {@code null} would let the optimizer prove the guard dead and prune the whole block.
   */
  static MessageEvent message;
  static ErrorEvent error;
  static WorkerLocation location;
  static MessagePort port;
  /** The DOM's {@code sequence<Transferable>} shape, as elemental2 expresses it. */
  static JsArray<Transferable> ports;
  /** The same transfer list as a plain Java array - the {@code @JsOverlay} convenience shape. */
  static Transferable[] portArray;
  static JsArrayString scripts;

  /** Sink for the anchor, so no reachable read can be dropped as unused. */
  static Object sink;

  /**
   * Raw JS entry points used to raise an error the way a real application would: from a native
   * timer callback, i.e. outside GWT's {@code $entry} wrapper, so it really becomes an uncaught
   * worker error instead of being swallowed by the exception handler.
   */
  @JsFunction
  interface JsTask {
    void run();
  }

  @JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "goog.global")
  static final class JsHost {
    private JsHost() {
    }

    @JsMethod(name = "setTimeout")
    static native void setTimeout(JsTask task, double delay);

    /** Calls a function that does not exist, so the worker raises a real TypeError. */
    @JsMethod(name = "gwtWebWorkerVerifyMissingFunction")
    static native void boom();
  }

  /** Always false at runtime, opaque to the optimizer. */
  static boolean never() {
    return System.currentTimeMillis() < 0;
  }

  @Override
  public void onWorkerLoad() {
    touchEverything();

    DedicatedWorkerGlobalScope scope = getGlobalScope();
    WorkerLocation scopeLocation = getLocation();
    WorkerGlobalScope self = scope.self();

    scope.setOnError(event -> {
      try {
        postMessage(PREFIX + "error:"
            + event.getMessage() + "|file=" + event.getFilename() + "|line=" + event.getLineNumber());
      } catch (Throwable t) {
        postMessage(PREFIX + "threw:onError:" + t);
      }
    });

    scope.setOnMessage(event -> onHostMessage(event, scope, scopeLocation, self));

    postMessage(PREFIX + "ready");
  }

  private void onHostMessage(MessageEvent event, DedicatedWorkerGlobalScope scope,
      WorkerLocation scopeLocation, WorkerGlobalScope self) {
    try {
      handleHostMessage(event, scope, scopeLocation, self);
    } catch (Throwable t) {
      // GWT's $entry wrapper around a @JsFunction hands a thrown exception to the
      // UncaughtExceptionHandler, which nothing sets in a worker - so a throwing handler looks
      // exactly like a hang, and the host just waits forever. Report it over the wire instead.
      postMessage(PREFIX + "threw:" + t);
    }
  }

  private void handleHostMessage(MessageEvent event, DedicatedWorkerGlobalScope scope,
      WorkerLocation scopeLocation, WorkerGlobalScope self) {
    String text = event.getDataAsString();

    if ("quit".equals(text)) {
      postMessage(PREFIX + "bye");
      // close() tears the worker down immediately; deferring it one turn gives the queued
      // postMessage above a chance to leave the worker before the queue is discarded.
      Scheduler.get().scheduleDeferred(this::close);
      return;
    }

    if ("json".equals(text)) {
      postMessage("{\"ok\":true,\"n\":42}");
      return;
    }

    if ("num".equals(text)) {
      postMessage(42.5);
      return;
    }

    if ("boom".equals(text)) {
      JsHost.setTimeout(JsHost::boom, 0);
      return;
    }

    if ("port".equals(text)) {
      // Runtime check of the 3.0.0 transfer-list API: the host transferred a real MessageChannel
      // port through Worker.postMessage(message, Transferable[]). If the Java-array-to-JS-array
      // conversion were wrong the browser would have thrown before this handler ever ran, and if
      // the port did not survive the transfer the reply below would never reach the host - the
      // harness would time out instead of passing.
      JsArray<MessagePort> received = event.getPorts();
      sink = received;
      int count = received == null ? -1 : received.getLength();
      if (count != 1) {
        // Only ever sent on failure; on success the reply travels over the transferred port
        // itself, which keeps the host's step machine free of ordering assumptions.
        postMessage(PREFIX + "port:badcount:" + count);
        return;
      }
      MessagePort transferred = received.getAt(0);
      transferred.start();
      transferred.postMessage("port-hello");
      return;
    }

    // Real MessageEvent accessors, on the event the browser actually delivered.
    message = event;
    sink = event.getDataAsNumber();
    sink = event.getLastEventId();
    sink = event.getSource();
    sink = event.getPorts();

    // Real WorkerLocation accessors, on the location the browser actually reports.
    String locationSummary = scopeLocation == null ? "<null>" : ""
        + "proto=" + scopeLocation.getProtocol()
        + ",host=" + scopeLocation.getHost()
        + ",hostname=" + scopeLocation.getHostname()
        + ",port=" + scopeLocation.getPort()
        + ",path=" + scopeLocation.getPathname()
        + ",search=" + scopeLocation.getSearch()
        + ",hash=" + scopeLocation.getHash()
        + ",href=" + scopeLocation.getHref();

    postMessage(PREFIX
        + "echo:" + text
        + "|self=" + (self == null ? "<null>" : "ok")
        + "|scope=" + (scope == null ? "<null>" : "ok")
        + "|origin=" + event.getOrigin()
        + "|" + locationSummary);
  }

  /**
   * Compile-time reachability anchor for the members that no runtime path in this harness can
   * reach (the host-side {@code Worker} API, {@code MessagePort}, and the {@code postMessage}
   * overloads that take a transfer list). Guarded by {@link #never()}, so it never runs.
   */
  static void touchEverything() {
    if (!never()) {
      return;
    }

    sink = message.getDataAsString();
    sink = message.getDataAsNumber();
    sink = message.getDataAsJSO();
    sink = message.getLastEventId();
    sink = message.getOrigin();
    sink = message.getPorts();
    sink = message.getSource();

    sink = error.getFilename();
    sink = error.getLineNumber();
    sink = error.getMessage();

    sink = location.getHash();
    sink = location.getHost();
    sink = location.getHostname();
    sink = location.getHref();
    sink = location.getPathname();
    sink = location.getPort();
    sink = location.getProtocol();
    sink = location.getSearch();

    port.close();
    port.postMessage(1.0);
    port.postMessage("s");
    port.postMessage(new Object());
    port.postMessage(1.0, ports);
    port.postMessage("s", ports);
    port.postMessage(new Object(), ports);
    port.postMessage(1.0, portArray);
    port.postMessage("s", portArray);
    port.postMessage(new Object(), portArray);
    port.setOnMessage(handler -> {
    });
    port.start();
    port.terminate();

    Worker worker = Worker.create("x.js");
    worker.postMessage(1.0);
    worker.postMessage("s");
    worker.postMessage(new Object());
    worker.postMessage(1.0, ports);
    worker.postMessage("s", ports);
    worker.postMessage(new Object(), ports);
    worker.postMessage(1.0, portArray);
    worker.postMessage("s", portArray);
    worker.postMessage(new Object(), portArray);
    worker.setOnMessage(handler -> {
    });
    worker.setOnError(handler -> {
    });
    worker.terminate();

    WorkerGlobalScope scope = DedicatedWorkerGlobalScope.get();
    scope.close();
    sink = scope.getLocation();
    sink = scope.self();
    scope.importScript("x.js");
    scope.importScripts(scripts);
    scope.importScripts(new String[] {"a", "b"});
    scope.setOnError(handler -> {
    });

    DedicatedWorkerGlobalScope dedicated = DedicatedWorkerGlobalScope.get();
    dedicated.postMessage(1.0);
    dedicated.postMessage("s");
    dedicated.postMessage(new Object());
    dedicated.postMessage(1.0, ports);
    dedicated.postMessage("s", ports);
    dedicated.postMessage(new Object(), ports);
    dedicated.postMessage(1.0, portArray);
    dedicated.postMessage("s", portArray);
    dedicated.postMessage(new Object(), portArray);
    dedicated.setOnMessage(handler -> {
    });
    dedicated.terminate();
  }
}
