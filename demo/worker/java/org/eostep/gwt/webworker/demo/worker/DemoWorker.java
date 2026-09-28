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
package org.eostep.gwt.webworker.demo.worker;

import com.google.gwt.core.client.Scheduler;
import org.eostep.gwt.webworker.client.DedicatedWorkerEntryPoint;
import org.eostep.gwt.webworker.client.DedicatedWorkerGlobalScope;
import org.eostep.gwt.webworker.client.MessageEvent;
import org.eostep.gwt.webworker.client.MessagePort;
import org.eostep.gwt.webworker.client.WorkerLocation;
import elemental2.core.JsArray;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/**
 * Worker half of the manual demo - this is the code that actually runs <em>inside</em> the browser
 * worker thread.
 *
 * <p>It is written the way the README tells consumers to write a worker: extend
 * {@link DedicatedWorkerEntryPoint}, put everything in {@code onWorkerLoad()}, and reach the
 * outside world only through {@code postMessage}. The module it lives in adds the
 * {@code dedicatedworker} linker, which is what makes GWT emit code that can run outside a
 * {@code window}.
 *
 * <p>The protocol is deliberately readable: the host sends short commands such as
 * {@code echo hello} or {@code json}, and every reply is either a plain payload (the numeric case
 * has to stay a raw JS number) or a {@code worker:}-prefixed line.
 */
public class DemoWorker extends DedicatedWorkerEntryPoint {

  private static final String PREFIX = "worker:";

  /**
   * Raw JS entry points.
   *
   * <p>{@code missingFunction} calls a function that does not exist, which is the only reliable
   * way to raise a <em>genuinely uncaught</em> worker error. A {@code throw} inside a GWT callback
   * would not do: GWT wraps callbacks in {@code $entry}, which hands the exception to
   * {@code GWT.getUncaughtExceptionHandler()} - unset in a worker - so it would vanish without
   * ever reaching the host's {@code onerror}. Calling it from a native {@code setTimeout} callback
   * puts the throw outside that wrapper.
   *
   * <p>{@code name = "goog.global"} is the documented way to reach the JS global object from a
   * native JsType; {@code goog.global} is emitted by the compiler in every module preamble, so it
   * needs no linker template variable.
   */
  @JsFunction
  interface JsTask {
    void run();
  }

  @JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "goog.global")
  static final class GoogGlobal {
    private GoogGlobal() {
    }

    @JsMethod(name = "setTimeout")
    static native void setTimeout(JsTask task, double delay);

    @JsMethod(name = "demoWorkerThisFunctionDoesNotExist")
    static native void missingFunction();
  }

  @Override
  public void onWorkerLoad() {
    DedicatedWorkerGlobalScope scope = getGlobalScope();

    // Worker-side error handler. In this browser the payload usually arrives with empty
    // message/filename/lineno; the host-side Worker.setOnError is the one the browser fills in.
    scope.setOnError(event -> postMessage(PREFIX + "error:"
        + event.getMessage() + " @" + event.getFilename() + ":" + event.getLineNumber()));

    scope.setOnMessage(event -> {
      try {
        handle(event);
      } catch (Throwable t) {
        // Without this the exception would go to the unset UncaughtExceptionHandler and the host
        // would just see silence - see the README's note about $entry swallowing callbacks.
        postMessage(PREFIX + "threw:" + t);
      }
    });

    postMessage(PREFIX + "ready|" + describe());
  }

  private void handle(MessageEvent event) {
    String text = event.getDataAsString();
    if (text == null) {
      postMessage(PREFIX + "unexpected:null");
      return;
    }

    if (text.startsWith("echo ")) {
      postMessage(PREFIX + "echo:" + text.substring("echo ".length()));
      return;
    }

    if (text.startsWith("number ")) {
      // Reply with a real JS number, not a string: postMessage(double) on the way out pairs with
      // MessageEvent.getDataAsNumber() on the host side.
      postMessage(Double.parseDouble(text.substring("number ".length())));
      return;
    }

    if ("json".equals(text)) {
      // A JSON *string*; the host parses it with MessageEvent.getDataAsJSO().
      postMessage("{\"from\":\"worker\",\"ok\":true,\"n\":42,\"tags\":[\"a\",\"b\"]}");
      return;
    }

    if ("location".equals(text)) {
      postMessage(PREFIX + "location|" + describe());
      return;
    }

    if ("boom".equals(text)) {
      postMessage(PREFIX + "boom:raising an uncaught TypeError in 0 ms");
      GoogGlobal.setTimeout(GoogGlobal::missingFunction, 0);
      return;
    }

    if ("port".equals(text)) {
      handleTransferredPorts(event);
      return;
    }

    if ("close".equals(text)) {
      postMessage(PREFIX + "bye");
      // close() discards the outbound queue immediately, so defer it one turn to give the reply
      // above a chance to leave the worker. Without this the host never sees "bye".
      Scheduler.get().scheduleDeferred(this::close);
      return;
    }

    postMessage(PREFIX + "unknown:" + text);
  }

  /**
   * Uses the {@code MessagePort} that the host transferred in through
   * {@code postMessage(message, Transferable[])}.
   *
   * <p>Reading it out of {@link MessageEvent#getPorts()} and calling {@code postMessage} on it is
   * what proves the transfer really happened - a port that was copied rather than transferred
   * cannot be used by the receiving side at all.
   */
  private void handleTransferredPorts(MessageEvent event) {
    JsArray<MessagePort> ports = event.getPorts();
    int count = ports == null ? -1 : ports.getLength();
    if (count != 1) {
      postMessage(PREFIX + "port:badcount:" + count);
      return;
    }

    MessagePort channel = ports.getAt(0);
    // Assigning onmessage implicitly starts the port; no explicit start() call is needed.
    channel.setOnMessage(incoming -> {
      try {
        channel.postMessage("worker-echo:" + incoming.getDataAsString());
      } catch (Throwable t) {
        channel.postMessage("worker-threw:" + t);
      }
    });

    channel.postMessage("worker-hello");
    postMessage(PREFIX + "port:ready");
  }

  /** What the worker can see about itself - the host prints this verbatim. */
  private static String describe() {
    DedicatedWorkerGlobalScope self = DedicatedWorkerGlobalScope.get();
    WorkerLocation location = self.getLocation();
    if (location == null) {
      return "location=<null>";
    }
    return "self=" + (self.self() == null ? "<null>" : "ok")
        + ",protocol=" + location.getProtocol()
        + ",host=" + location.getHost()
        + ",hostname=" + location.getHostname()
        + ",port=" + location.getPort()
        + ",pathname=" + location.getPathname()
        + ",href=" + location.getHref();
  }
}
