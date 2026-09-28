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
package org.eostep.gwt.webworker.verify.host;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.ImageElement;
import com.google.gwt.http.client.URL;
import org.eostep.gwt.webworker.client.ErrorEvent;
import org.eostep.gwt.webworker.client.MessageEvent;
import org.eostep.gwt.webworker.client.MessagePort;
import org.eostep.gwt.webworker.client.Worker;
import elemental2.core.Transferable;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/**
 * Host half of the verification harness.
 *
 * <p>Drives the worker built from {@code WorkerSmoke} through a real browser and asserts the
 * replies. Results are written into the page so a headless browser dump can read them.
 *
 * <p>Besides the plain message exchange this also transfers a real {@code MessageChannel} port into
 * the worker and expects it back - the only way to exercise the transfer-list overloads of
 * {@code postMessage} against real browser objects rather than merely compiling them.
 */
public class HostSmoke implements EntryPoint {

  private static final StringBuilder LOG = new StringBuilder();

  private static int failures;

  /**
   * Minimal JsInterop binding for the JS {@code MessageChannel} constructor.
   *
   * <p>The library deliberately binds no {@code MessageChannel} of its own - its scope is the
   * {@code Worker} / {@code MessagePort} surface - so the harness declares the four lines it
   * needs. Public fields on a native JsType are JS properties; {@code new MessageChannelLike()}
   * emits {@code new MessageChannel()}.
   */
  @JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "MessageChannel")
  static final class MessageChannelLike {
    public MessagePort port1;
    public MessagePort port2;
  }

  private final MessageChannelLike channel = new MessageChannelLike();

  private Worker worker;
  private int step;

  @Override
  public void onModuleLoad() {
    worker = Worker.create("workersmoke/workersmoke.nocache.js");
    worker.setOnError(event -> guard("onWorkerError", () -> handleWorkerError(event)));
    worker.setOnMessage(event -> guard("onWorkerMessage", () -> handleMessage(event)));
    // The host keeps port1; port2 is what gets transferred to the worker.
    channel.port1.setOnMessage(event -> guard("onChannelMessage", () -> handleChannelMessage(event)));
    log("created worker, waiting for its ready message");
  }

  /**
   * GWT's {@code $entry} wrapper swallows an exception thrown inside a JS callback - it hands it to
   * the {@code UncaughtExceptionHandler}, which is unset here - so a throwing handler would
   * otherwise look exactly like a hang. Routing every callback through this guard puts the failure
   * on the page instead.
   */
  private interface Callback {
    void run();
  }

  private void guard(String name, Callback callback) {
    try {
      callback.run();
    } catch (Throwable t) {
      fail(name + " threw " + t);
      finish();
    }
  }

  /**
   * The worker is deliberately made to throw during step 3, so this is an expected event, not a
   * failure. It is also where the host-side {@code ErrorEvent} accessors get verified for real.
   */
  private void handleWorkerError(ErrorEvent event) {
    log("host onError: message=" + event.getMessage()
        + " file=" + event.getFilename() + " line=" + event.getLineNumber());
    check("ErrorEvent delivered to the host's Worker.setOnError",
        event.getMessage() != null
            && event.getMessage().contains("gwtWebWorkerVerifyMissingFunction"));
    check("ErrorEvent.getFilename() populated", event.getFilename() != null);
    check("ErrorEvent.getLineNumber() populated", event.getLineNumber() > 0);
    send("num");
    step = 4;
  }

  private void handleMessage(MessageEvent event) {
    String text = event.getDataAsString();
    log("recv[" + step + "]: " + text);

    // The numeric reply must be handled before anything string-ish. MessageEvent.getDataAsString()
    // hands back the raw JS value without coercing it - exactly what the JSNI version's
    // `return this.data;` did - so for a numeric payload `text` is a JS number, not a String.
    if (step == 4) {
      double number = event.getDataAsNumber();
      log("  getDataAsNumber() = " + number);
      check("postMessage(double) + MessageEvent.getDataAsNumber()", number == 42.5);
      // Note: getDataAsString() returns the raw JS value with no coercion (same as the old JSNI
      // `return this.data;`), so on this numeric payload it is a JS number and any String method
      // on it throws. That cannot be asserted here because GWT statically knows the expression's
      // Java type is String and folds `instanceof String` to true; it was observed as
      // "TypeError: text_0.startsWith is not a function" before this branch was reordered.
      transferPort();
      step = 5;
      return;
    }

    if (text != null && text.startsWith("worker:error:")) {
      // The worker's own setOnError handler. Recorded as an observation: in this environment the
      // worker-scope ErrorEvent arrives with empty message/filename/lineno, which is why the
      // ErrorEvent assertions are made against the host-side event instead.
      log("  note: worker-scope onerror payload = " + text.substring("worker:error:".length()));
      return;
    }

    switch (step) {
      case 0:
        check("worker announced itself with postMessage", "worker:ready".equals(text));
        send("ping");
        break;
      case 1:
        check("message round-trip host -> worker -> host", text.startsWith("worker:echo:ping"));
        check("WorkerGlobalScope.self() resolved", text.contains("|self=ok"));
        check("getGlobalScope() resolved", text.contains("|scope=ok"));
        check("WorkerLocation accessors returned real values",
            text.contains("proto=http") && !text.contains("hostname=<null>"));
        send("json");
        break;
      case 2:
        check("MessageEvent.getDataAsJSO() parsed a JSON reply", event.getDataAsJSO() != null);
        send("boom");
        step = 3;
        return; // step 4 is reached from handleWorkerError
      case 5:
        // Reached only if the worker reports it could not use the transferred port. On success
        // the worker stays silent here and the next thing to happen is the channel message.
        fail("worker could not use the transferred MessagePort: " + text);
        finish();
        return;
      case 6:
        check("worker acknowledged close()", text.startsWith("worker:bye"));
        worker.terminate();
        finish();
        return;
      default:
        fail("unexpected reply at step " + step + ": " + text);
        finish();
        return;
    }
    step++;
  }

  /**
   * Transfers the channel's second port into the worker using the {@code Transferable[]} overload.
   *
   * <p>This is the one part of the 3.0.0 API surface no other step touches: the transfer list is
   * now {@code elemental2.core.JsArray<Transferable>}, or a Java {@code Transferable[]} that is
   * reinterpreted as one. If that reinterpretation did not yield a genuine JS array, the browser
   * would reject the call with a TypeError here, before the worker ever saw it.
   */
  private void transferPort() {
    log("transfer: channel.port2 -> worker");
    worker.postMessage("port", new Transferable[] {channel.port2});
  }

  /**
   * The transferred port, now owned by the worker, posted a message back through it.
   *
   * <p>That one event proves three things at once: the port survived the transfer, the worker
   * could read it out of {@code MessageEvent.getPorts()}, and {@code MessagePort.postMessage} on
   * the worker side reaches the host.
   */
  private void handleChannelMessage(MessageEvent event) {
    check("MessagePort transferred through postMessage(message, Transferable[]) round-tripped",
        "port-hello".equals(event.getDataAsString()));
    send("quit");
    step = 6;
  }

  private void send(String message) {
    log("send: " + message);
    worker.postMessage(message);
  }

  private static void check(String name, boolean ok) {
    if (ok) {
      log("PASS  " + name);
    } else {
      failures++;
      log("FAIL  " + name);
    }
  }

  private static void fail(String message) {
    failures++;
    log("FAIL  " + message);
  }

  private static void log(String message) {
    LOG.append(message).append('\n');
    Element out = Document.get().getElementById("out");
    if (out != null) {
      out.setInnerText(LOG.toString());
    }
  }

  private void finish() {
    step = 6;
    String verdict = failures == 0 ? "PASS" : "FAIL(" + failures + ")";
    Element result = Document.get().getElementById("result");
    if (result != null) {
      result.setInnerText(verdict);
    }
    log("verdict: " + verdict);
    report(verdict);
  }

  /**
   * Reports the verdict and the whole log back to the harness server with a plain image GET.
   *
   * <p>Reading the result out of the page with {@code chrome --dump-dom} is not reliable here:
   * {@code --virtual-time-budget} cuts the dump at a nondeterministic point while worker messages
   * are still in flight, so the dump sometimes ends before the exchange does. A request the server
   * records is deterministic, and an {@code <img>} is the one channel that needs no CORS
   * preflight, no response handling and no library support.
   */
  private static void report(String verdict) {
    ImageElement beacon = Document.get().createImageElement();
    beacon.setSrc("report.gif?verdict=" + URL.encodeQueryString(verdict)
        + "&log=" + URL.encodeQueryString(LOG.toString()));
    Document.get().getBody().appendChild(beacon);
  }
}
