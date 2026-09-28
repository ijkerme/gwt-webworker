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
package org.eostep.gwt.webworker.demo.host;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.dom.client.Element;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.VerticalPanel;
import org.eostep.gwt.webworker.client.ErrorEvent;
import org.eostep.gwt.webworker.client.MessageEvent;
import org.eostep.gwt.webworker.client.MessagePort;
import org.eostep.gwt.webworker.client.Worker;
import elemental2.core.Transferable;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

/**
 * Host half of the manual demo - the page you actually open in a browser.
 *
 * <p>Every button drives one piece of the library so that "is the worker working?" can be answered
 * by clicking rather than by reading a test log:
 *
 * <ul>
 *   <li>start / terminate a {@link Worker}
 *   <li>string round trip, numeric round trip, JSON round trip
 *   <li>{@code WorkerLocation} and {@code self()} as the worker sees them
 *   <li>a genuinely uncaught worker error, surfaced through {@code Worker.setOnError}
 *   <li>a real {@code MessageChannel} port transferred into the worker (the 3.0.0 transfer-list
 *       API) and used for two-way traffic
 *   <li>the worker closing itself with {@code close()}
 * </ul>
 *
 * <p>Note what is <em>not</em> here: a worker needs a real origin, so this page must be served over
 * {@code http(s)}. Opening {@code index.html} from disk gives
 * "Failed to construct 'Worker': Script at 'file:///...' cannot be accessed from origin 'null'".
 * Use GWT's own DevMode - {@code mvn -o -DskipTests -Pgwt-demo-devmode install} - which serves the
 * page and recompiles this class on every reload, or the linked build via {@code demo/serve.py}.
 */
public class DemoHost implements EntryPoint {

  private static final String WORKER_URL = "demoworker/demoworker.nocache.js";

  /**
   * Minimal JsInterop binding for the JS {@code MessageChannel} constructor.
   *
   * <p>The library deliberately binds no {@code MessageChannel} - its scope is the
   * {@code Worker} / {@code MessagePort} surface - so the demo declares the four lines it needs.
   * Public fields on a native JsType are JS properties, and {@code new MessageChannelLike()}
   * emits {@code new MessageChannel()}.
   */
  @JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "MessageChannel")
  static final class MessageChannelLike {
    public MessagePort port1;
    public MessagePort port2;
  }

  private final VerticalPanel root = new VerticalPanel();
  private final Label status = new Label();
  private final TextBox input = new TextBox();
  private final TextArea log = new TextArea();

  private Worker worker;
  private MessageChannelLike channel;

  @Override
  public void onModuleLoad() {
    buildUi();
    setStatus("worker 未启动", false);
    append("用法：先点「启动 Worker」，等状态变成「worker 已就绪」再试其它按钮。");
    append("这个页面必须通过 http(s) 访问（用 demo/serve.py），file:// 直接打开无法创建 worker。");
  }

  // ---------------------------------------------------------------- UI

  private void buildUi() {
    root.setStyleName("demo-root");

    status.setStyleName("demo-status");

    input.setStyleName("demo-input");
    input.setText("你好，worker");
    input.setVisibleLength(28);

    log.setStyleName("demo-log");
    log.setReadOnly(true);
    log.setVisibleLines(22);
    log.setCharacterWidth(110);

    HorizontalPanel lifecycle = row();
    lifecycle.add(button("启动 Worker", this::startWorker));
    lifecycle.add(button("终止 Worker（terminate）", this::terminateWorker));
    lifecycle.add(button("清空日志", () -> log.setText("")));

    HorizontalPanel messaging = row();
    messaging.add(input);
    messaging.add(button("发送文本", this::sendText));
    messaging.add(button("发送数字 42.5", this::sendNumber));
    messaging.add(button("发送 JSON", () -> send("json")));

    HorizontalPanel introspection = row();
    introspection.add(button("读取 WorkerLocation / self()", () -> send("location")));
    introspection.add(button("触发 worker 未捕获错误", () -> send("boom")));
    introspection.add(button("让 worker 自行 close()", () -> send("close")));

    HorizontalPanel channelRow = row();
    channelRow.add(button("转移 MessageChannel 端口", this::transferPort));
    channelRow.add(button("通过端口发送文本", this::sendOverPort));

    root.add(status);
    root.add(lifecycle);
    root.add(messaging);
    root.add(introspection);
    root.add(channelRow);
    root.add(log);

    RootPanel panel = RootPanel.get("demo");
    (panel == null ? RootPanel.get() : panel).add(root);
  }

  private static HorizontalPanel row() {
    HorizontalPanel row = new HorizontalPanel();
    row.setStyleName("demo-row");
    return row;
  }

  private Button button(String label, Runnable action) {
    Button button = new Button(label);
    button.setStyleName("demo-button");
    // GWT's $entry wrapper would swallow a throwing handler and leave the page looking frozen,
    // so every entry point goes through guard().
    button.addClickHandler(event -> guard("按钮「" + label + "」", action));
    return button;
  }

  // ---------------------------------------------------------------- worker lifecycle

  private void startWorker() {
    if (worker != null) {
      append("!! worker 已在运行；先「终止 Worker」再启动");
      return;
    }
    append("host: new Worker(\"" + WORKER_URL + "\")");
    worker = Worker.create(WORKER_URL);
    worker.setOnMessage(event -> guard("Worker.setOnMessage", () -> onWorkerMessage(event)));
    worker.setOnError(event -> guard("Worker.setOnError", () -> onWorkerError(event)));
    setStatus("已启动，等待 worker 就绪…", false);
  }

  private void terminateWorker() {
    if (worker == null) {
      append("!! worker 未启动");
      return;
    }
    worker.terminate();
    worker = null;
    channel = null;
    setStatus("worker 未启动", false);
    append("host: worker.terminate() 已调用");
  }

  // ---------------------------------------------------------------- host -> worker

  private void sendText() {
    send("echo " + input.getValue());
  }

  private void sendNumber() {
    // The command is a string; the *reply* is the raw number that exercises
    // postMessage(double) / getDataAsNumber().
    send("number 42.5");
  }

  private boolean send(String command) {
    if (worker == null) {
      append("!! worker 未启动 - 请先点「启动 Worker」");
      return false;
    }
    append("host → worker: " + command);
    worker.postMessage(command);
    return true;
  }

  private void transferPort() {
    if (worker == null) {
      append("!! worker 未启动 - 请先点「启动 Worker」");
      return;
    }
    if (channel != null) {
      append("!! 端口已经转移过了；重启 worker 可以再来一次");
      return;
    }
    channel = new MessageChannelLike();
    channel.port1.setOnMessage(event -> guard("channel.port1.onmessage", () -> {
      append("port1 收到（经 MessageChannel）: " + event.getDataAsString());
    }));
    append("host → worker: \"port\" + 转移 channel.port2   [postMessage(String, Transferable[])]");
    // The transfer list is a plain Java array here; the library reinterprets it as a JS array.
    worker.postMessage("port", new Transferable[] {channel.port2});
  }

  private void sendOverPort() {
    if (channel == null) {
      append("!! 还没有端口 - 请先点「转移 MessageChannel 端口」");
      return;
    }
    String payload = input.getValue();
    append("port1 → worker: " + payload);
    channel.port1.postMessage(payload);
  }

  // ---------------------------------------------------------------- worker -> host

  private void onWorkerMessage(MessageEvent event) {
    // A reply's type is a runtime fact, not something the host can predict: a reply to
    // `number <d>` is a JS number, every other reply is a JS string. Ask the value instead of
    // tracking "the next message is the number" in a flag - with two requests in flight (click
    // 「发送文本」 and then 「发送数字 42.5」) the two replies come back in their own order, the flag
    // mis-attributes them, and the number reaches getDataAsString() where `.startsWith` does not
    // exist. Both accessors read the same JS property with different Java types and never coerce,
    // so choosing the wrong one is a silent type error rather than a compile error.
    Object data = Js.asPropertyMap(event).get("data");
    if (isJsNumber(data)) {
      append("worker → host: " + event.getDataAsNumber()
          + "   [postMessage(double) → getDataAsNumber()]");
      return;
    }

    String text = event.getDataAsString();
    append("worker → host: " + text);
    if (text == null) {
      return;
    }

    if (text.startsWith("worker:ready")) {
      setStatus("worker 已就绪", true);
      append("  ↳ worker 自我描述：" + text.substring("worker:ready|".length()));
    } else if (text.startsWith("worker:port:ready")) {
      append("  ↳ 端口已就绪，现在可以用「通过端口发送文本」双向通信");
    } else if (text.startsWith("worker:port:badcount")) {
      append("  ↳ 传输列表里的端口数量不对，说明转移没有成功");
    } else if (text.startsWith("worker:bye")) {
      append("  ↳ worker 自己调用了 close()，已退出");
      worker = null;
      channel = null;
      setStatus("worker 已自行 close()", false);
    } else if (text.startsWith("worker:threw")) {
      append("  ↳ worker 的 onmessage 里抛了异常（本应被 $entry 吞掉，这是防护网抓到的）");
    }
  }

  /**
   * True when a reply arrived as a raw JS number rather than a JS string.
   *
   * <p>{@code jsinterop.base.Js.typeOf} would say this outright, and it is the natural choice - but
   * GWT 2.13.1 does not resolve it. It is the one member of {@code Js} that is annotated
   * {@code @JsMethod(namespace = "<window>")} ({@code jsinterop-annotations} 2.0.0 renamed the
   * global namespace to {@code "<global>"}), and the compiler rejects the call with
   * "The method typeOf(Object) is undefined for the type Js" even though the class file declares
   * it. {@code Js.debugger()}, {@code Js.isFalsy()}, {@code Js.coerceToDouble()} and the rest all
   * resolve. Strict equality against the {@code +} coercion is the same test with members that do
   * work: {@code 42.5 === +42.5} is true, while {@code "42.5" === +"42.5"} and {@code "x" === +"x"}
   * are both false - the coercion converts a numeric string, but {@code ===} does not.
   */
  private static boolean isJsNumber(Object data) {
    return Js.isTripleEqual(data, Js.coerceToDouble(data));
  }

  private void onWorkerError(ErrorEvent event) {
    append("worker 未捕获错误 → host 的 Worker.setOnError:");
    append("    message = " + event.getMessage());
    append("    file    = " + event.getFilename());
    append("    line    = " + event.getLineNumber());
    setStatus("worker 抛出了未捕获错误（见日志）", false);
  }

  // ---------------------------------------------------------------- plumbing

  /**
   * Runs an action and puts any exception on the page.
   *
   * <p>GWT wraps every callback in {@code $entry}, which routes a thrown exception to
   * {@code GWT.getUncaughtExceptionHandler()} - unset here - so an unguarded handler failure would
   * look exactly like a frozen page.
   */
  private void guard(String where, Runnable action) {
    try {
      action.run();
    } catch (Throwable t) {
      append("!! " + where + " 抛异常: " + t);
    }
  }

  private void setStatus(String text, boolean ready) {
    status.setText(text);
    status.setStyleName(ready ? "demo-status demo-status-ok" : "demo-status demo-status-idle");
  }

  private void append(String line) {
    String current = log.getText();
    log.setText(current.isEmpty() ? line : current + "\n" + line);
    Element element = log.getElement();
    element.setScrollTop(element.getScrollHeight());
  }
}
