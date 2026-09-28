# 迁移记录：gwt-webworker → GWT 2.13.1

本文档记录 `gwt-webworker` 从 GWT 2.8.2 迁移到当前最新版 GWT 的完整过程：模块范围与依赖关系、
关键技术调整、行为变更、兼容性矩阵、风险清单，以及验证证据。README 只保留使用说明，技术细节
都在这里。

本文档同时记录 4.0.0 的**包名重构**（`com.google.gwt.webworker` → `org.eostep.gwt.webworker`），
见 §0.1。

---

## 0. 结论摘要

| 项目 | 迁移前 | 迁移后 |
|---|---|---|
| GWT 版本 | 2.8.2 | **2.13.1**（2026-06-17，当前最新） |
| Maven 坐标 | `com.google.gwt:gwt-user` | **`org.gwtproject:gwt-user`** |
| Java 基线 | 1.8 | **11** |
| GWT 构建插件 | `org.codehaus.mojo:gwt-maven-plugin:2.8.2`（2017 年后停更） | **`net.ltgt.gwt.maven:gwt-maven-plugin:1.3.0`** |
| 模块描述 | 带 2.8.2 DTD | **无 DTD** |
| 库自身包名 | `com.google.gwt.webworker` | **`org.eostep.gwt.webworker`**（Java 包 + GWT 模块名，4.0.0） |
| 客户端 API 实现 | 8 个类全部 `JavaScriptObject` + JSNI | **8 个类全部 JsInterop，零 JSNI**（`MessagePort` 于 3.0.0 收尾） |
| 传输列表类型 | `com.google.gwt.core.client.JsArray<MessagePort>` | **`elemental2.core.JsArray<Transferable>`** + `Transferable[]` 便利重载 |
| 新增依赖 | — | **`com.google.elemental2:elemental2-core:1.2.3`**（含 `elemental2-promise` / `jsinterop-base` 传递依赖） |
| 产物版本 | 1.0.4 | **4.0.0**（GWT/Java 基线变更 + 传输列表签名变更 + 包名变更，均为破坏性变更） |

验证结果：`mvn -o -DskipTests clean install` 成功；`mvn -o -DskipTests -Pgwt-verify verify` 两个 harness
模块 `Compile of permutations succeeded` + `Link succeeded`；真实 Chromium 端到端 **PASS（12/12）**，
含一次真实 `MessageChannel` 端口转移往返。4.0.0 包名重构后四条路线（库本体 / harness / demo 链接构建 /
demo DevMode）全部重跑通过，见 §6.8。

`src/` 与 `verify/` 下 `grep -rn '/\*-{'` 返回**空**——JSNI 已清零。

另有一个可手动点击的 demo（`mvn -o -DskipTests -Pgwt-demo package` + `python demo/serve.py`），
两个模块同样 `Compile of permutations succeeded` + `Link succeeded`，并在真实 Chromium 中按顺序点完
全部按钮验证通过（§6.6）。这一阶段额外发现两个坑：`Js.typeOf` 在 2.13.1 上不可解析（§5.9）、
以及用标志位预测下一条消息类型必然出错（§5.10）。

同一个 demo 也可以用 **GWT 自带的 DevMode**（Super Dev Mode）跑：`mvn -o -DskipTests -Pgwt-demo-devmode install`
起在 127.0.0.1:8888，宿主模块每次刷新都会重编译（§6.7）。但 worker 模块**不能**被 SDM 托管
（§5.11），只能预编译成静态文件。

### 0.1 包名重构（4.0.0）

原包名 `com.google.gwt.webworker` 借用了 Google 的命名空间——它既不是 GWT 的一部分，也不归本项目
所有，在任何同时依赖 GWT 的环境里都属于应当避免的"伪命名空间"。4.0.0 把它整体搬到
`org.eostep.gwt.webworker`：

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| Java 包 | `com.google.gwt.webworker.{client,linker}` | **`org.eostep.gwt.webworker.{client,linker}`** |
| GWT 模块名 | `com.google.gwt.webworker.WebWorker` | **`org.eostep.gwt.webworker.WebWorker`** |
| `<inherits>` 写法 | `com.google.gwt.webworker.WebWorker` | **`org.eostep.gwt.webworker.WebWorker`** |
| linker 类名 | `com.google.gwt.webworker.linker.DedicatedWorkerLinker` | **`org.eostep.gwt.webworker.linker.DedicatedWorkerLinker`** |
| 模板资源路径 | `com/google/gwt/webworker/linker/DedicatedWorkerTemplate.js` | **`org/eostep/gwt/webworker/linker/DedicatedWorkerTemplate.js`** |

**关键约束：GWT 自身的 `com.google.gwt.*` 引用一律不动。** 本次只改本项目自己写的那一个包，
`com.google.gwt.core.Core`、`com.google.gwt.useragent.UserAgent`、`com.google.gwt.dev.*`
（Maven 里调用编译器/DevMode 的主类）、`com.google.gwt.dom.*`、`com.google.gwt.http.*`、
`com.google.gwt.user.*` 全部保持原样。改完后全仓分词统计确认残留的 `com.google.gwt.<token>`
只有 GWT 自身的 `core` / `user` / `useragent` / `dom` / `dev` / `http` 六个 token。

因为包名与模块名都变了，任何 `<inherits>`、`import`、`<add-linker>` 之外的 linker 类名引用都会
失效，所以这是一次**破坏性变更**，对应主版本号 4.0.0。逐项影响见 §4.3。

---

## 1. 模块范围与依赖关系

### 1.1 模块边界

本仓库是**单模块** Maven 工程（`packaging=jar`），产出一个 GWT **库**（不是应用）。它不包含
任何页面或服务端代码。

```
gwt-webworker (jar)
├── src/main/java/org/eostep/gwt/webworker/
│   ├── client/          12 个类：Web Worker API 的 JS 绑定 + 入口点基类
│   └── linker/          1 个类：自定义 GWT Linker
└── src/main/resources/org/eostep/gwt/webworker/
    ├── WebWorker.gwt.xml                    模块描述文件（对外契约）
    └── linker/DedicatedWorkerTemplate.js    选择脚本模板（对外契约）
```

`verify/` 是本次新增的**验证 harness**，不属于发布产物。`demo/` 是给人手点的示例页 + worker
+ 本地 http 服务脚本，同样不进入发布产物（只在 `gwt-demo` profile 下编译）。

### 1.2 依赖关系

```
                    ┌──────────────────────────────────────┐
                    │  宿主 GWT 应用（消费者）              │
                    └───────────────┬──────────────────────┘
                                    │ <inherits name="org.eostep.gwt.webworker.WebWorker"/>
                                    │ <add-linker name="dedicatedworker"/>
                                    ▼
   ┌────────────────────────────────────────────────────────────┐
   │ gwt-webworker                                              │
   │                                                            │
   │  WebWorker.gwt.xml ──inherits──▶ com.google.gwt.core.Core  │
   │                    ──inherits──▶ com.google.gwt.useragent.UserAgent   ← 2.0.0 新增
   │                    ──inherits──▶ elemental2.core.Core                 ← 3.0.0 新增
   │                    ──define-linker──▶ DedicatedWorkerLinker           │
   │                    ──entry-point──▶ GwtWebWorker（空实现）             │
   │                                                            │
   │  DedicatedWorkerLinker ──extends──▶ SelectionScriptLinker  │
   │                       ──reads────▶ DedicatedWorkerTemplate.js         │
   │                                                            │
   │  client/* ──JsInterop──▶ jsinterop.annotations（随 gwt-user 传递）     │
   │  client/* ──传输列表───▶ elemental2.core.JsArray / Transferable        │
   │           ──便利重载───▶ jsinterop.base.Js.uncheckedCast              │
   └────────────────────────────────────────────────────────────┘
```

`elemental2.core.Core` 自身又继承 `jsinterop.base.Base` 与 `elemental2.promise.Promise`，因此消费
方通过 Maven 传递依赖自动获得 `elemental2-core`、`jsinterop-base`、`elemental2-promise` 三个 jar，
无需手工声明。

### 1.3 对 GWT 编译器的隐式契约（最容易被忽略、也最容易在升级时炸掉的部分）

这套库的价值一半在 Java 类，另一半在**运行时约定**，升级时必须一起核对：

| 契约 | 位置 | 2.13.1 是否仍然成立 |
|---|---|---|
| 选择脚本模板占位符 `__MODULE_FUNC__` / `__MODULE_NAME__` / `__PERMUTATIONS_BEGIN__` / `__PERMUTATIONS_END__` | `DedicatedWorkerTemplate.js` | ✅ 逐字比对 2.13.1 的 `SelectionScriptLinker.fillSelectionScriptTemplate` 与 `permutations.js`，完全一致 |
| `gwtOnLoad(errFn, moduleName, strongName, softPermutationId)` 四参签名 | 同上 | ✅ 与 2.13.1 的 `SingleScriptTemplate.js` 调用点一致 |
| `SelectionScriptLinker` 的 4 个可覆盖点（`getCompilationExtension` / `getModulePrefix` / `getModuleSuffix2` / `getSelectionScriptTemplate`） | `DedicatedWorkerLinker` | ✅ 抽象方法签名未变；`getModuleSuffix2` 返回 `null` 会报错，本库返回 `""` |
| 模板把 `$self` / `$wnd` / `$doc` 赋为 **worker 全局变量** | 模板第 17–22 行 | ✅ 仍然必需：GWT 标准 Linker 只在选择脚本函数内声明 `var $wnd = window`，而编译产物 `.cache.js` 是另一个通过 `importScripts` 加载的脚本，看不到那些局部变量 |
| `goog.global` 由编译器 preamble 生成 | `GenerateJavaScriptAST` | ✅ 编译器始终生成 `$wnd.goog.global = $wnd.goog.global \|\| $wnd`，配合模板的 `$wnd = self` 正好指向 worker 全局对象 |

> 这一层是"行为不变"的真正风险点：Java 代码改错了编译器会报错，模板改错了只在运行时炸。

---

## 2. 关键技术调整

### 2.1 依赖管理

**坐标体系迁移（强制）。** GWT 2.10.0 是最后一个使用 `com.google.gwt` groupId 的版本，之后全部
发布到 `org.gwtproject`。本地仓库里 `com.google.gwt:gwt-user:2.12.1` 只有下载失败的
`.lastUpdated` 占位文件——这个坐标在 2.10.0 之后**不存在**。

```xml
<!-- 迁移前 -->
<dependency>
  <groupId>com.google.gwt</groupId><artifactId>gwt-user</artifactId><version>2.8.2</version>
</dependency>

<!-- 迁移后：用 BOM 统一仲裁，避免 gwt-user / gwt-dev / jsinterop-annotations 版本漂移 -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.gwtproject</groupId><artifactId>gwt</artifactId>
      <version>2.13.1</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>org.gwtproject</groupId><artifactId>gwt-user</artifactId><scope>provided</scope></dependency>
  <dependency><groupId>org.gwtproject</groupId><artifactId>gwt-dev</artifactId><scope>provided</scope></dependency>
</dependencies>
```

注意：**jar 内部的 Java 包名没变**（仍是 `com.google.gwt.*`），所以业务代码的 `import` 一行都不用改。

**⚠️ 不要沿用"排除 ecj"的老办法。** 这是一条会导致编译器直接瘫痪的陷阱：

| | gwt-dev 2.12.2 | gwt-dev 2.13.1 |
|---|---|---|
| 自带 `org/eclipse/jdt/internal/compiler/*` 类数 | **801** | **0** |
| JDT 来源 | 打包进 jar | 依赖 `org.eclipse.jdt:org.eclipse.jdt.core:3.33.0` → 传递引入 **`org.eclipse.jdt:ecj:3.33.0`** |

在 2.12 时代流传的"`gwt-dev` 里排掉 `ecj` 以避免遮蔽自带 JDT"的做法，在 2.13 会**把唯一的
JDT 编译器删掉**。已核实：`SourceTypeBinding` 只存在于 `ecj-3.33.0.jar`，`org.eclipse.jdt.core`
里没有。

**移除已死的基础设施。** `nexus-staging-maven-plugin` + `wagon-webdav-jackrabbit` + OSSRH 的
`distributionManagement` / snapshot 仓库 / `<prerequisites>`：OSSRH 服务已退役，这些配置不再
产生任何效果。发布应改用 Sonatype Central Portal。

**签名改为按需。** `maven-gpg-plugin` 原本无条件绑定在 `verify` 阶段，而 `verify` 是 `install`
生命周期的一部分——在没有 GPG 私钥（或有口令）的机器上，`mvn install` 会卡在 gpg 提示上。
已移入 `release` profile：`mvn -Prelease deploy`。

### 2.2 编译配置

| 项 | 迁移前 | 迁移后 | 理由 |
|---|---|---|---|
| `maven.compiler.source/target` | `1.8` | — | 换用 `maven.compiler.release` |
| `maven.compiler.release` | — | **`11`** | GWT 2.13 dev tools 运行需要 Java 11+；2.13 官方说明这是**最后一个支持 Java 11 的版本系列**。用 `release` 而非 `target`，javac 才会按 Java 11 API 做检查 |
| GWT 插件 | `org.codehaus.mojo:gwt-maven-plugin:2.8.2` | `net.ltgt.gwt.maven:gwt-maven-plugin:1.3.0` | codehaus 插件 2017 年停更，无法驱动 GWT 2.10+ |
| `-sourceLevel` | 未显式设置 | **显式 `11`** | ⚠️ GWT 2.13.1 的默认值**仍是 1.8**（`ArgHandlerSourceLevel.getDefaultArgs()` 返回 `JAVA8`），不显式设置会踩到 Java 9+ 语法解析失败 |
| `-style` | `OBFUSCATED` | `PRETTY` | 仅为便于验证；生产可改回 |
| `failOnError` | `true` | `true` | 保持 |
| `<goal>test</goal>` / `<goal>resources</goal>` | 有 | 去掉 | 本仓库没有任何 GWT 测试；`resources` 也不是新插件的 goal（资源拷贝由 maven-resources-plugin 负责） |

GWT 插件执行：

```xml
<plugin>
  <groupId>net.ltgt.gwt.maven</groupId>
  <artifactId>gwt-maven-plugin</artifactId>
  <version>1.3.0</version>
  <executions>
    <execution>
      <id>gwt-compile</id>
      <goals><goal>compile</goal></goals>   <!-- 默认绑定 prepare-package -->
      <configuration>
        <moduleName>org.eostep.gwt.webworker.WebWorker</moduleName>
        <webappDirectory>${project.build.directory}/gwt/war</webappDirectory>
        <workDir>${project.build.directory}/gwt/work</workDir>
        <sourceLevel>11</sourceLevel>
        <failOnError>true</failOnError>
      </configuration>
    </execution>
  </executions>
</plugin>
```

> 这次自检编译只证明**模块描述与 Linker 类可用**，不证明 API 可用——见 §5.2 的裁剪陷阱。

### 2.3 模块描述文件 `WebWorker.gwt.xml`

```xml
<!-- 迁移前 -->
<!DOCTYPE module PUBLIC "-//Google Inc.//DTD Google Web Toolkit 2.8.2//EN"
 "http://gwtproject.org/doctype/2.8.2/gwt-module.dtd">
<module>
  <inherits name="com.google.gwt.core.Core" />
  <define-linker name="dedicatedworker" class="...DedicatedWorkerLinker" />
  <entry-point class="org.eostep.gwt.webworker.client.GwtWebWorker" />
</module>
```

```xml
<!-- 迁移后 -->
<module>
  <inherits name="com.google.gwt.core.Core" />
  <inherits name="com.google.gwt.useragent.UserAgent" />   <!-- 新增，见下 -->
  <define-linker name="dedicatedworker" class="...DedicatedWorkerLinker" />
  <entry-point class="org.eostep.gwt.webworker.client.GwtWebWorker" />
</module>
```

**改动一：删除 DOCTYPE。** GWT 2.13.1 自带的描述文件（`Core.gwt.xml`、`useragent/UserAgent.gwt.xml`、
`dom/DOM.gwt.xml` …）**全部不带 DOCTYPE**，直接从 `<module>` 开始。带版本的 `gwt-module.dtd` 已不再
发布。这里选择**删除**而不是"升级到 2.13.1 的 DTD"，与官方保持一致，同时顺带消除离线/代理环境下
编译期取 DTD 失败的隐患。

**改动二：新增 `com.google.gwt.useragent.UserAgent` 继承（这是一个真实的缺陷修复）。**

README 一直要求宿主写：

```xml
<inherits name="org.eostep.gwt.webworker.WebWorker" />
<set-property name="user.agent" value="safari" />
```

但 `com.google.gwt.core.Core` **并不定义** `user.agent`——它只由 `com.google.gwt.user.User`
（或 `useragent.UserAgent`）引入。一个只继承本库的 worker 模块因此无法按文档固定 permutation。
本次实测确认了这个缺陷：

```
[ERROR] Line 9: Property 'user.agent' not found
```

修复方式是让本库自己继承 `com.google.gwt.useragent.UserAgent`（旧名 `com.google.gwt.user.UserAgent`
只是已弃用的别名，其文件头自己写了"新代码请继承 `c.g.g.useragent.UserAgent`"）。这样文档里的集成
片段在 GWT 2.13.1 下真正可用。

**`user.agent` 的取值变化。** GWT 2.13.1 中该属性只有两个值：

```xml
<define-property name="user.agent" values="gecko1_8" />
<extend-property name="user.agent" values="safari" />
```

IE 支持已在 GWT 2.10.0 移除，`ie8/ie9/ie10/ie11` 全部消失。所以 README 里 `value="safari"` 的写法
**依然有效**，但可选集合从多个收敛到两个。

### 2.4 API 层：`JavaScriptObject` + JSNI → JsInterop

这是"采用最新 GWT 技术"的主要落点。JsInterop（`@JsType` / `@JsProperty` / `@JsMethod` /
`@JsFunction` / `@JsOverlay`）是 GWT 2.8 起引入、此后一直推广的现代 JS 互操作方式；JSNI 是旧方式，
且 GWT 编译器对 native JsType 明确禁止 JSNI：

```
[ERROR] JSNI method %s is not allowed in a native JsType.
```

改造前后对照（以 `Worker` 为例）：

```java
// 迁移前：JavaScriptObject 覆盖类型 + JSNI 函数体
public class Worker extends AbstractWorker {
  public static native Worker create(String url) /*-{ return new Worker(url); }-*/;

  public final native void postMessage(String message) /*-{ this.postMessage(message); }-*/;

  public final native void setOnMessage(MessageHandler messageHandler) /*-{
    this.onmessage = function(event) {
      @...Worker::onMessageImpl(...)(messageHandler, event);
    }
  }-*/;
}

// 迁移后：JsInterop 原生类型，JSNI 全部消失
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "Worker")
public class Worker extends AbstractWorker {

  public Worker(String url) { }                       // → new Worker(url)

  @JsOverlay
  public static Worker create(String url) { return new Worker(url); }

  public final native void postMessage(String message);   // → this.postMessage(message)

  @JsOverlay
  public final void setOnMessage(MessageHandler messageHandler) {
    setOnMessageHandler(event -> HandlerDispatch.onMessage(messageHandler, event));
  }

  @JsProperty(name = "onmessage")
  private native void setOnMessageHandler(MessageHandler messageHandler);
}
```

**逐类改造结果：**

| 类 | 迁移前 | 迁移后 |
|---|---|---|
| `AbstractWorker` | `JavaScriptObject` + JSNI | `@JsType` native，`@JsProperty("onerror")` |
| `Worker` | `JavaScriptObject` + JSNI | `@JsType` native，4 个 `postMessage` 重载保留 |
| `WorkerGlobalScope` | `JavaScriptObject` + JSNI | `@JsType` native，`@JsProperty("self")` |
| `DedicatedWorkerGlobalScope` | `JavaScriptObject` + JSNI | `@JsType` native，`get()` 走 `goog.global.self` |
| `WorkerLocation` | `JavaScriptObject` + JSNI | `@JsType` native，8 个 `@JsProperty` |
| `MessageEvent` | `JavaScriptObject` + JSNI | `@JsType` native；`getDataAsJSO()` 改为 `@JsOverlay` + `JSON.parse` 绑定 |
| `ErrorEvent` | `JavaScriptObject` + JSNI | `@JsType` native，3 个 `@JsProperty` |
| `MessagePort` | `JavaScriptObject` + JSNI | **保持原样**（原因见 §6.1），仅修复缺陷 |
| `MessageHandler` / `ErrorHandler` | 普通接口 | **`@JsFunction`**：实现即 JS 函数，可直接赋给 `onmessage` |
| `DedicatedWorkerEntryPoint` / `GwtWebWorker` | 纯 Java | 不变 |
| `DedicatedWorkerLinker` | Linker | 不变（2.13.1 API 兼容） |

**新增的内部辅助类（非公共 API）：**

- `HandlerDispatch` —— 把原先散落在 4 处的 `UncaughtExceptionHandler` 包装逻辑收敛成一处；
- `Json` —— `JSON` 全局对象的 JsInterop 绑定，替代 `MessageEvent.getDataAsJSO()` 里的 JSNI；
- `JsGlobal` —— 绑定 `goog.global` 静态字段以读取 worker 全局对象，替代旧 JSNI 的 `return $self;`。

**重载为什么能保留。** JsInterop 通常禁止同一 JS 名冲突，但 `JsInteropRestrictionChecker`
的 `checkInstanceNameConsistency` 对 native 成员提前返回：

```java
if (member.isJsNative()) {
  return;      // native members cannot collide
}
```

所以 `Worker` / `DedicatedWorkerGlobalScope` / `MessagePort` 上 4 个 `postMessage` 重载可以原样保留，
映射到同一个 JS 方法名 `postMessage`——与旧 JSNI 的行为完全一致。

---

## 3. 行为变更清单

### 3.1 保持不变的部分

- **公共 API 签名**：所有类型名、方法名、参数类型、返回类型、可见性、`final`/`native`/`static`
  修饰全部保持。消费者代码无需修改即可编译。
- **运行时行为**：消息收发、`onmessage`/`onerror` 注册、`WorkerLocation` 取值、`JSON.parse`
  语义、`getDataAsString()` **不做类型转换**（见 §6.4）、`close()` 立即丢弃发送队列（见 §6.5）
  —— 全部与迁移前一致。
- **Linker / 模板 / 模块契约**：见 §1.3，全部不变。

### 3.2 有意的变更（4 项）

| # | 变更 | 类型 | 说明 |
|---|---|---|---|
| 1 | `MessagePort.setOnMessage` 修复 | **缺陷修复** | 原 JSNI 把未定义标识符 `handler` 传给 dispatch 调用（参数名其实是 `messageHandler`），每次投递都求值一个未定义变量，**注册的回调从未被调用过**。现已改走 `HandlerDispatch` |
| 2 | `MessageEvent.getDataAsJSO()` 实现替换 | 等价替换 | `JSON.parse(this.data)` → JsInterop 绑定，语义一致 |
| 3 | `DedicatedWorkerGlobalScope.get()` / `WorkerGlobalScope.self()` 取值来源 | 等价替换 | 旧：读模板注入的全局变量 `$self`；新：读 `goog.global.self` / `this.self`。同一对象，且不再依赖模板执行顺序 |
| 4 | `MessageHandler` / `ErrorHandler` 变为 `@JsFunction` | **语义增强** | 实现体现在**就是** JS 函数。匿名类/方法引用/lambda 写法都不受影响；但该接口不能再继承其他接口或增加第二个抽象方法 |

### 3.3 升级前未验证到的既有缺陷

| 位置 | 问题 | 状态 |
|---|---|---|
| `MessagePort.setOnMessage` 的 JSNI body | 引用未定义标识符 `handler`，回调从未被调用 | ✅ 2.0.0 修复 |
| `postMessage(..., JsArray<MessagePort>)` | 第二个参数在规范里是 **Transferable 列表**，不只是 `MessagePort` | ✅ 3.0.0 修正为 `JsArray<Transferable>` / `Transferable[]` |
| `MessagePort.terminate()` | `MessagePort` 规范里没有 `terminate()`，调用会抛 `TypeError` | ⬜ 保留（见 §5.8） |
| `DedicatedWorkerGlobalScope.terminate()` | JS 侧 `DedicatedWorkerGlobalScope` 没有 `terminate()`，只有 `close()` | ⬜ 保留（见 §5.8） |
| `MessageEvent.getSource()` | 返回类型声明为 `String`，实际是 `MessagePort`/`WindowProxy` 对象 | ⬜ 保留（见 §5.8） |

---

## 4. 兼容性矩阵

### 4.1 消费者视角

| 场景 | 是否兼容 | 说明 |
|---|---|---|
| 只调用 `postMessage(String)` / `postMessage(double)` / `setOnMessage` / `setOnError` / `Worker.create` / `close()` / `getLocation()` | ✅ 源码级兼容 | 这些签名一字未改 |
| 调用 `postMessage(msg, JsArray<MessagePort>)` | ❌ **编译失败** | 传输列表改为 `elemental2.core.JsArray<Transferable>` 或 `Transferable[]`。旧写法把 `ports` 换成 `new MessagePort[]{...}` 或 `JsArray.of(...)` 即可 |
| 声明 `JsArray<MessagePort> ports`（GWT 的 `JsArray`） | ❌ **编译失败** | ① `JsArray<T extends JavaScriptObject>` 不再接受 `MessagePort`；② 需改 `import elemental2.core.JsArray` |
| `MessageEvent.getPorts()` 的接收方 | ⚠️ 需改 import | 返回类型由 GWT `JsArray<MessagePort>` 变为 `elemental2.core.JsArray<MessagePort>`；`getAt(i)` / `getLength()` 可用 |
| 把 `MessagePort` 当 `JavaScriptObject` 用（`port.cast()`、`MessagePort.createArray()`、`JavaScriptObject o = port;`） | ❌ **编译失败** | 3.0.0 起 `MessagePort` 是 JsInterop 原生类型，不再继承 `JavaScriptObject` |
| 实现了 `MessageHandler` / `ErrorHandler` | ✅ | 匿名类、lambda、方法引用均可；不能继承扩展 |
| 对库类型做 `instanceof` | ⚠️ | `@JsType(isNative=true)` 与 `JavaScriptObject` 的 `instanceof` 语义不同 |
| `JavaScriptObject o = messageEvent;` | ❌ **编译失败** | JsInterop 原生类型不再继承 `JavaScriptObject`。这是 JsInterop 迁移的固有代价 |
| 自己的代码里写 JSNI 操作库类型 | ⚠️ | native JsType 里禁止 JSNI（`JSNI method ... is not allowed in a native JsType`），需改用 JsInterop |
| 运行环境 Java 8 | ❌ | 需 Java 11+（javac 编译产物为 Java 11 字节码） |
| 运行环境 GWT < 2.10 | ❌ | 依赖 `org.gwtproject` 坐标、`@JsFunction` 与 elemental2，请使用 1.0.4（或 2.x） |

### 4.2 编译期矩阵（本次实测）

| 组合 | 结果 |
|---|---|
| GWT 2.13.1 + 原始源码 + 完整 API harness | 通过（`Link succeeded`） |
| GWT 2.13.1 + JsInterop 源码 + 完整 API harness | 通过（`Link succeeded`） |
| GWT 2.12.2 + 触发 §6.2 的写法 | 同样崩溃（非 2.13 回归） |
| GWT 2.13.1 + `MessagePort` 的 native 方法丢失 JSNI body | **编译器内部异常**（§6.2） |

### 4.3 包名重构的影响（4.0.0）

| 场景 | 是否兼容 | 需要改成 |
|---|---|---|
| `<inherits name='com.google.gwt.webworker.WebWorker' />` | ❌ **编译失败** | `<inherits name='org.eostep.gwt.webworker.WebWorker' />`（模块名就是包名，改名后旧名找不到） |
| `import com.google.gwt.webworker.client.*;` | ❌ **编译失败** | `import org.eostep.gwt.webworker.client.*;`（或 `Worker` / `MessageEvent` / `MessageHandler` 等逐个改） |
| `<add-linker name="dedicatedworker" />` | ✅ 不变 | linker 的**短名**由模块描述文件里的 `<define-linker>` 决定，与包名无关 |
| 直接写 linker 全类名（少见） | ❌ | `com.google.gwt.webworker.linker.DedicatedWorkerLinker` → `org.eostep.gwt.webworker.linker.DedicatedWorkerLinker` |
| 依赖 `DedicatedWorkerTemplate.js` 的资源路径 | ❌ | 路径随包走：`com/google/gwt/webworker/linker/…` → `org/eostep/gwt/webworker/linker/…` |
| 自己的 `com.google.gwt.*` 包下写了类并 `inherits` 本库 | ⚠️ | 只要不改自己的包名就不受影响；GWT 自身的 `com.google.gwt.core` / `user` / `useragent` 等仍按原样继承 |
| 只依赖 jar 的传递依赖（elemental2 / jsinterop-base / gwt-user） | ✅ 不变 | 坐标与版本都没动 |

> 改包名这件事本身不涉及任何行为变化：类的数量、方法签名、linker 逻辑、模板内容都一字未动，
> 只是它们换了命名空间。但"编译期解析"这件事全靠包名，所以它依然是破坏性变更。

---

## 5. 潜在风险与注意事项

### 5.1 曾经卡住 `MessagePort` 的硬约束（3.0.0 已解除）

2.0.0 时 `Worker`、`DedicatedWorkerGlobalScope`、`MessagePort` 都声明了
`postMessage(..., JsArray<MessagePort>)`，而：

```java
public class JsArray<T extends JavaScriptObject> { ... }   // com.google.gwt.core.client
```

泛型上界强制类型实参必须是 `JavaScriptObject` 子类；同时 GWT 拒绝
`@JsType(isNative = true)` 继承 `JavaScriptObject`：

```
[ERROR] Native JsType 'X' can only extend native JsType classes.
```

两个约束叠加的结果是：只要这些签名还在，`MessagePort` 就无法 JsInterop 化。

3.0.0 的解法是**去掉上界**而不是绕过它：`elemental2.core.JsArray<T>` 对 `T` 无上界，而
`elemental2.core.Transferable` 正是 DOM 规范在
`postMessage(message, sequence<Transferable>)` 里点名的标记接口。改用这两个类型后约束消失，
`MessagePort` 成为普通 JsInterop 原生类型。代价是一次源码不兼容的签名变更（见 §4.1）。

### 5.2 ⚠️ 绿色的 GWT 编译可能什么都没验证

本库自带一个**空实现的 entry point**，所以 GWT 的 DCE 会把整个库裁掉再"编译成功"。本次踩到两次：

1. 第一版 harness 用局部变量 `MessagePort port = null;` 配合 `if (port != null) { port.close(); }`
   ——优化器证明 `port` 恒为 `null`，整块代码被删，`MessagePort` 根本没进编译。用
   `grep -c MessagePort <war>/*/*.cache.js` 得到 0 才暴露出来。
2. 第二版改用静态字段后，纯属性读取语句（`message.getLastEventId();`）又因为"结果未被使用且无副作用"
   被删掉。

现在的 harness 用**静态字段 + sink 赋值 + 真实对象执行**三重手段保证覆盖面。**任何对"编译通过"的
信任都必须先做符号存在性检查**：

```bash
grep -o "hostname\|lastEventId\|importScripts" target/gwt/www/*/*.cache.js | sort -u
```

### 5.3 ⚠️ `JavaScriptObject` 子类上的无 body `native` 方法会崩溃编译器

这是本次最耗时的发现，且**在 2.12.2 和 2.13.1 上都可复现**，所以它不是 2.13 的回归，而是长期存在的
GWT 缺陷。最小复现：

```java
public class JsoPort extends JavaScriptObject {
  protected JsoPort() { }
  public final native void close();          // 注意：没有 JSNI body
}
// 入口点里调用 port.close();
```

结果：

```
com.google.gwt.dev.jjs.InternalCompilerException: Unexpected error during visit.
  at MakeCallsStatic$CreateStaticImplsVisitor.getOrCreateStaticImpl
  at Devirtualizer$RewriteVirtualDispatches.ensureDevirtualVersionExists
Caused by: java.lang.NullPointerException: ... because "node" is null
```

原因链：`JavaScriptObject` 子类上的无 body `native` 方法被 GWT 当成**隐式 JsInterop 成员**；当它
还需要一个去虚拟化的静态实现时，`MakeCallsStatic` 取 `x.getBody()` 得到 `null`，随后
`rewriter.accept(null)` 空指针。

**规避方式**：`JavaScriptObject` 子类的 `native` 方法**必须保留 JSNI body**。GWT 自己就是这么写的
——`JsArrayString` 里每个方法都是 `public final native String join(String separator) /*-{ ... }-*/;`
而不是裸 `native`。本次改造中我一度把 `MessagePort` 的 JSNI body 清掉，正是这个原因导致编译崩溃。

**3.0.0 之后本仓库已无 `JavaScriptObject` 子类**（`grep -rn "extends JavaScriptObject" src verify`
只命中注释），所以这个陷阱在本项目内已不可触发；但你自己的 JSO overlay 仍然适用。

### 5.4 `-sourceLevel` 默认值仍是 1.8

GWT 2.13.1 的 `ArgHandlerSourceLevel.getDefaultArgs()` 返回 `SourceLevel.JAVA8`。不显式传
`-sourceLevel` 时，Java 9+ 语法（如匿名类上的钻石操作符）会在**第三方 jar 的解析阶段**报错，
错误信息指向依赖包、看起来像依赖坏了。本项目统一显式设置为 11。

### 5.5 JsInterop 对 native JsType 的成员限制

native JsType 内所有成员都会被当作 JS 成员，因此：

- 不能有任何**带方法体的非 `@JsOverlay` 方法**（`Native JsType method %s should be native or abstract.`）；
- 不能有 `@JsIgnore`；
- 字段不能 `final`、不能有初始化器；
- 构造器必须是空体。

本次把需要逻辑的部分（异常分发、`JSON.parse`、`importScripts(String[])`）统一放到了包级私有的
普通 Java 类或 `@JsOverlay` 方法里。

### 5.6 构建与发布侧风险

- `mvn install` 现在会在 `prepare-package` 阶段跑一次 GWT 编译（模块自检），比迁移前慢约 10 秒。
  如需跳过，可用 `-Dgwt.compiler.skip` 或直接不触发该阶段。
- GPG 签名改为 `-Prelease` 手动启用：**发布脚本必须同步修改**，否则会发布未签名产物。
- OSSRH 相关配置已删除。若仍向 OSSRH 发布，需要改回；若迁到 Central Portal，需要补
  `central-publishing-maven-plugin`。
- 验证 harness 是**可选 profile**（`-Pgwt-verify`），不影响正常构建；但 CI 建议把
  `mvn -Pgwt-verify verify` + `verify/run-e2e.py` 作为门禁，否则 §5.2 的裁剪陷阱会悄悄回归。

### 5.7 `--dump-dom` 取证不可靠（验证方法学）

`chrome --headless=new --dump-dom --virtual-time-budget=N` 在 worker 消息在途时会**在不确定的位置
截断**（实测同一页面在不同轮次分别截在 `send: ping`、`send: boom`、`recv[4]`）。因此 harness 改为
把结论用图片 GET 回传到本地服务，由服务端记录——这是确定性的。如果沿用 dump 方式，会得到"随机失败"
的假象。

### 5.8 有意保留的既有缺陷（未修）

3.0.0 刻意保持为**纯 JSNI→JsInterop 转换**，不在同一次改动里顺带修正行为，因此以下三处仍保持原样，
仅在 javadoc 中标注：

| 成员 | 问题 | 正确做法 |
|---|---|---|
| `MessagePort.terminate()` | 映射为 `this.terminate()`，而 `MessagePort` 没有这个方法，调用抛 `TypeError: this.terminate is not a function`。规范里 `terminate()` 属于 `Worker`，应是复制粘贴遗留 | 删除，或改为 `close()` |
| `DedicatedWorkerGlobalScope.terminate()` | 同上——worker 全局作用域只有 `close()` | 同上 |
| `MessageEvent.getSource()` | 声明返回 `String`，规范是 `MessageEventSource?`（`WindowProxy` / `MessagePort` / `ServiceWorker`）。非空时会得到"被当成 Java String 的 JS 对象" | 改为返回 JsInterop 类型 |

它们都**没有**在本次 harness 中被调用（`touchEverything()` 只引用、不执行），所以保留不影响验证结论。

### 5.9 `Js.typeOf` 在 GWT 2.13.1 上不可解析（demo 阶段新发现）

`jsinterop.base.Js.typeOf(Object)` 是"问一个 JS 值到底是什么类型"最自然的选择，`base-1.0.1.jar`
里的 class 文件确实声明了它，但调用它**编译不过**：

```
[ERROR] Line 240: The method typeOf(Object) is undefined for the type Js
```

区分点只有一个：它是 `Js` 里**唯一**带 `@JsMethod(namespace = "<window>")` 的成员，而
`jsinterop-annotations` 2.0.0 已把全局命名空间改名为 `"<global>"`。同一个类里用
`@JsProperty(namespace = "<window>", ...)` 的 `undefined()` / `arguments()` / `debugger()` 都能解析，
`Js.isFalsy()` / `Js.isTruthy()` / `Js.coerceToDouble()` / `Js.isTripleEqual()` / `Js.uncheckedCast()`
也都能解析（逐个加进 demo 模块实测）。所以这不是 classpath 问题，也不是
`javaemul.internal.annotations.HasNoSideEffects` 缺失（`Js.uncheckedCast` 同样带 javaemul 注解且可用）。

替代写法（`demo/host/.../DemoHost.java` 的 `isJsNumber`）：

```java
// 42.5 === +42.5        -> true
// "42.5" === +"42.5"    -> false   （+ 会转换数字字符串，=== 不会）
// "x" === +"x"          -> false   （+"x" 是 NaN）
private static boolean isJsNumber(Object data) {
  return Js.isTripleEqual(data, Js.coerceToDouble(data));
}
```

### 5.10 不要用"下一条消息就是它"这类标志位推断消息类型

这条不是 GWT 的限制，而是 demo 实测踩出来的：宿主原本用一个 `expectNumber` 布尔量表示"下一条
worker 消息是数字"。只要同时有两个请求在途（先点「发送文本」再点「发送数字 42.5」），两条回复的
到达顺序不受控，标志位就会张冠李戴——数字被 `getDataAsString()` 读走，于是：

```
worker → host: worker:echo:你好，worker   [postMessage(double) → getDataAsNumber()]   ← 串了
worker → host: 42.5
!! Worker.setOnMessage 抛异常: (TypeError) : text_0.startsWith is not a function
```

正确做法是**看值的实际类型**（§5.9 的 `isJsNumber`），而不是预测。同样的道理适用于
`verify/` 的 harness：断言里不要假设 `worker:port:ready` 一定先于端口回显到达。

### 5.11 Super Dev Mode 只接受 `CrossSiteIFrameLinker` 及其子类

DevMode 同时传入宿主与 worker 两个模块时，会在**绑定端口之前**整体失败：

```
[ERROR] linkers other than CrossSiteIFrameLinker aren't supported.
        Found: org.eostep.gwt.webworker.linker.DedicatedWorkerLinker
```

原因：SDM 需要自己的 linker 来注入重编译客户端（`sdm` 继承自 `CrossSiteIFrameLinker`），而
dedicated worker 必须用 `DedicatedWorkerLinker`（继承自 `SelectionScriptLinker`），二者互斥。
**结论：worker 模块永远无法被 SDM 实时重编译**，只能作为预编译静态文件放进 war 目录，DevMode 只
传宿主模块。对应 profile 见 §8 的 `gwt-demo-devmode`。

### 5.12 DevMode 的 `-workDir` 必须已存在

`CodeServer.ensureWorkDir` 只**检查**工作目录存在与否，不创建它，于是 DevMode 在绑定端口前就死：

```
java.io.IOException: workspace directory doesn't exist: target\gwt\devmode-work
	at com.google.gwt.dev.codeserver.CodeServer.ensureWorkDir(CodeServer.java:209)
```

报错读起来像配置错误，实际只是目录没建。注意 GWT **编译器**（`com.google.gwt.dev.Compiler`）会自己
创建 `-workDir`，所以只有 DevMode / code server 这条路会踩到。`gwt-demo-devmode` profile 用 antrun
`<mkdir>` 显式建目录。

---

## 6. 验证证据

### 6.1 构建

```
$ mvn -o -DskipTests clean install
[INFO] Building gwt-webworker 4.0.0
[INFO] Compiling 16 source files with javac [debug release 11] to target\classes
[INFO]    Compiling module org.eostep.gwt.webworker.WebWorker
[INFO]    Compile of permutations succeeded
[INFO]    Link succeeded
[INFO] BUILD SUCCESS
（安装 gwt-webworker-4.0.0.jar / -sources.jar / -javadoc.jar）
```

> **`clean` 不是可选的。** 4.0.0 的包名重构之后，`mvn install`（不带 `clean`）会**通过**，但
> `maven-resources-plugin` 只往 `target/classes` 里**拷**新资源、不删旧资源，于是改包前留下的
> `com/google/gwt/webworker/WebWorker.gwt.xml` 与 `.../linker/DedicatedWorkerTemplate.js` 仍在
> `target/classes` 里，并被原样打进 jar。实测：不 clean 时 jar 里**同时**存在旧包与新包两份资源。
> 两个模块名互不冲突，所以编译链接照样成功——是个静默的产物污染。核对手段：
>
> ```
> unzip -l target/gwt-webworker-4.0.0.jar | grep -c 'com/google/gwt/webworker'   # 必须为 0
> ```

### 6.2 全 API 可达性 + 链接

```
$ mvn -o -DskipTests -Pgwt-verify verify
[INFO] --- exec:3.4.1:exec (gwt-verify-compile) @ gwt-webworker ---
   Compile of permutations succeeded
   Link succeeded
   Compile of permutations succeeded
   Link succeeded
[INFO] BUILD SUCCESS
```

两个模块分别是：`WorkerSmoke`（用 `dedicatedworker` linker，真跑在 worker 里）与 `HostSmoke`
（普通 linker，驱动 worker）。`WorkerSmoke` 内用静态字段 + sink 引用**全部**公共成员。

### 6.3 真实浏览器端到端

```
$ python verify/run-e2e.py target/gwt/www 8311
VERDICT PASS
PASS  worker announced itself with postMessage
PASS  message round-trip host -> worker -> host
PASS  WorkerGlobalScope.self() resolved
PASS  getGlobalScope() resolved
PASS  WorkerLocation accessors returned real values
PASS  MessageEvent.getDataAsJSO() parsed a JSON reply
PASS  ErrorEvent delivered to the host's Worker.setOnError
PASS  ErrorEvent.getFilename() populated
PASS  ErrorEvent.getLineNumber() populated
PASS  postMessage(double) + MessageEvent.getDataAsNumber()
PASS  MessagePort transferred through postMessage(message, Transferable[]) round-tripped   ← 3.0.0 新增
PASS  worker acknowledged close()
verdict: PASS
```

第 11 项是 3.0.0 唯一新增的运行时断言，也是**唯一真正跑通传输列表重载**的路径。流程：

1. 宿主 `new MessageChannel()`，把 `port1` 留在宿主侧并挂 `setOnMessage`，`port2` 作为传输列表
   `new Transferable[] {channel.port2}` 交给 `worker.postMessage("port", ...)`；
2. worker 从 `MessageEvent.getPorts()` 取出该端口，`start()` 后 `postMessage("port-hello")`；
3. 宿主的 `port1.onmessage` 收到 `port-hello`，断言通过。

也就是说这一项同时验证了：`Transferable[]` → JS 数组的转换真实成立（否则浏览器在步骤 1 就会抛
`TypeError`）、端口真的完成了转移、`MessageEvent.getPorts()` 可用、worker 侧 `MessagePort.postMessage`
能回到宿主。编译产物中的对应形态：

```js
port1.onmessage = makeLambdaFunction(...)      // @JsProperty(name="onmessage") 直出 JS 属性
worker.postMessage('port', [ ..., port2 ])     // Js.uncheckedCast(Transferable[]) 发射为 JS 数组字面量
```

真实取值样本（`WorkerLocation` 8 个访问器全部返回浏览器真实值）：

```
worker:echo:ping|self=ok|scope=ok|origin=|
proto=http:,host=127.0.0.1:8311,hostname=127.0.0.1,port=8311,
path=/workersmoke/workersmoke.nocache.js,search=,hash=,
href=http://127.0.0.1:8311/workersmoke/workersmoke.nocache.js
```

宿主侧 `ErrorEvent`（消息/文件/行号均由浏览器填充）：

```
message=Uncaught TypeError: $wnd.goog.global.gwtWebWorkerVerifyMissingFunction is not a function
file=http://127.0.0.1:8311/workersmoke/2495E94A3EFAB92992B2838294D3F179.cache.js
line=1090
```

### 6.4 未能运行时验证的部分（诚实说明）

- `MessagePort` 在 3.0.0 起已获得**真实运行时验证**（§6.3 第 11 项：真实 `MessageChannel` 端口经
  传输列表进入 worker，再经 `MessagePort.postMessage` 回到宿主）。仍未覆盖的是：`MessagePort.close()`、
  以及 `MessagePort.setOnMessage` 在真实端口上的**回调触发**（本次是宿主侧 `port1` 的 `setOnMessage`
  被触发，worker 侧拿到端口后只调了 `start()` / `postMessage()`）。`MessagePort.terminate()` 有意不调用。
- worker 作用域的 `onerror` 事件在本环境里 `message`/`filename`/`lineno` 为空
  （`worker:error:undefined|file=undefined|line=undefined`），因此 `ErrorEvent` 的断言改在**宿主侧**
  的 `Worker.setOnError` 上做（该路径完整可用）。worker 侧的现象已记录，未断言，建议后续单独排查。
- `Worker` / `DedicatedWorkerGlobalScope` 的传输列表重载只在 `touchEverything()` 的不可达分支里被
  引用（编译期可达性锚点），未在浏览器中执行；真正跑通的是 `Worker.postMessage(String, Transferable[])`
  这一条路径（§6.3），它与其余重载共用同一个 `Js.uncheckedCast` 转换，故其余重载的风险等价。

### 6.5 已知的行为观察（保持原样，非本次引入）

`MessageEvent.getDataAsString()` 不做类型转换——它直接映射到 JS 的 `data` 属性，和旧的
`return this.data;` 完全一致。因此当负载是数字时，返回的是 **JS number**，对它调用任何 `String`
方法会抛：

```
TypeError: text_0.startsWith is not a function
```

这一点与迁移前行为一致（旧 JSNI 同样不转换），属于"保持行为不变"的范畴，但很容易踩到，已在
README 中标注。注意 GWT 会按**静态**类型推断：如果表达式已经被推断成 `String`，那
`x instanceof String` 会被常量折叠成 `true`，不能用它做运行时判断；只有在静态类型是 `Object`
时（例如从 `Js.asPropertyMap(event).get("data")` 取出来）`instanceof` 才是真检查。demo 用的是
§5.9 的 `Js.isTripleEqual` 写法。

### 6.6 手动 demo 的真实浏览器验证

`demo/` 是给人点的页面，不是自动断言，所以它的证据是"在真实 Chromium 里跑一遍并记录日志"：

```
$ mvn -o -DskipTests -Pgwt-demo package
Compiling module org.eostep.gwt.webworker.demo.host.DemoHost
   Compilation succeeded -- 6.238s
   Link succeeded
Compiling module org.eostep.gwt.webworker.demo.worker.DemoWorker
   Compilation succeeded -- 0.572s
   Link succeeded
[INFO] BUILD SUCCESS

$ python demo/serve.py --port 8321 --no-open   # 然后 headless Chrome --dump-dom
demohost.nocache.js Content-Type: text/javascript
UI probes: 14/14 present
GWT widgets rendered: True
RESULT: PASS
```

再用脚本按顺序点完所有按钮，日志（节选）证明每条通路都活着：

```
host: new Worker("demoworker/demoworker.nocache.js")
worker → host: worker:ready|self=ok,protocol=http:,host=127.0.0.1:8323,...   ← 8 个 WorkerLocation 访问器 + self()
host → worker: echo 你好，worker
host → worker: number 42.5
worker → host: worker:echo:你好，worker
worker → host: 42.5   [postMessage(double) → getDataAsNumber()]              ← §5.9 的类型判定
worker → host: {"from":"worker","ok":true,"n":42,"tags":["a","b"]}
worker → host: worker:location|self=ok,protocol=http:,...
host → worker: "port" + 转移 channel.port2   [postMessage(String, Transferable[])]
port1 收到（经 MessageChannel）: worker-hello
worker → host: worker:port:ready
port1 → worker: 你好，worker
port1 收到（经 MessageChannel）: worker-echo:你好，worker
```

整个过程宿主侧无 `!! ... 抛异常` 记录。`demo/` 的验证脚本是**一次性**的，跑完即删，仓库里只留
`demo/`（页面 + worker + `serve.py`）本身。

### 6.7 用 GWT 自带 DevMode 运行（`gwt-demo-devmode`）

```
$ mvn -o -DskipTests -Pgwt-demo-devmode install
...
   Loading Java files in org.eostep.gwt.webworker.demo.host.DemoHost.
   Module setup completed in 1958 ms

The code server is ready at http://127.0.0.1:9876/
GET /recompile/demohost
   Job ...DemoHost_1_0  Compiling module ...  Compilation succeeded -- 1.312s
GET /recompile/demohost
   Job ...DemoHost_1_1  skipped compile because no input files have changed
```

war 目录只放 `index.html` + `demoworker/`（§5.11），宿主模块由 code server 现场编译。真实 Chromium
下同样拿到 §6.6 的完整日志（14/14 中文探针命中，ready / echo / 42.5 / json / location / 端口双向
全通，宿主侧无异常）。

**实时重编译实测**：改一行 `DemoHost` 的状态文案，不跑 Maven、不重启 DevMode，只重新加载页面：

```
GET /recompile/demohost
   Job ...DemoHost_1_2
      Compiling module org.eostep.gwt.webworker.demo.host.DemoHost
         Linking per-type JS with 21 new/changed types.        ← 检测到 21 个类型变化
         Compilation succeeded -- 1.376s
```

新文案随即出现在页面上（`--dump-dom` 命中）。注意取证时**要把探针放进 `Label`（div）而不是日志
`TextArea`**：textarea 的 value 是 JS 属性，`--dump-dom` 序列化不到它——第一次就是这么误判成"没重编译"的。

### 6.8 包名重构后的复验（4.0.0）

改包名属于"编译期解析全靠字符串"的改动，所以四条路线全部重跑了一遍：

| 路线 | 命令 | 结果 |
|---|---|---|
| 库本体 | `mvn -o -DskipTests clean install` | ✅ BUILD SUCCESS；`Compiling module org.eostep.gwt.webworker.WebWorker` + `Link succeeded`；产出 `gwt-webworker-4.0.0.jar` |
| 端到端 harness | `mvn -o -DskipTests -Pgwt-verify verify` → `python verify/run-e2e.py target/gwt/www 8311` | ✅ 两个模块 `Link succeeded`；**VERDICT PASS，12/12**，含 `MessagePort` 传输往返 |
| demo（链接构建） | `mvn -o -DskipTests -Pgwt-demo package` | ✅ `DemoHost` / `DemoWorker` 两个模块均 `Link succeeded` |
| demo（DevMode） | `mvn -o -DskipTests -Pgwt-demo-devmode verify` / `install` | ✅ 构建接线通过；真起 DevMode 后 8888/9876 均 LISTENING，`/index.html` 200，`demohost/demohost.nocache.js` 与 `demoworker/demoworker.nocache.js` 均由 code server 提供 |

demo 页面用无头 Chromium 驱动点击**全部 11 个按钮**，两条路线都拿到同一份完整日志：

```
BUTTONS: 11
host: new Worker("demoworker/demoworker.nocache.js")
worker → host: worker:ready|self=ok,...,href=http://127.0.0.1:8888/demoworker/demoworker.nocache.js
host → worker: echo 你好，worker          worker → host: worker:echo:你好，worker
host → worker: number 42.5                worker → host: 42.5   [postMessage(double) → getDataAsNumber()]
host → worker: json                       worker → host: {"from":"worker","ok":true,"n":42,"tags":["a","b"]}
host → worker: location                   worker → host: worker:location|self=ok,...
host → worker: "port" + 转移 channel.port2   [postMessage(String, Transferable[])]
port1 收到（经 MessageChannel）: worker-hello
port1 收到（经 MessageChannel）: worker-echo:你好，worker
host → worker: boom                       worker 未捕获错误 → host 的 Worker.setOnError: message/file/line 均有值
host → worker: close                      worker → host: worker:bye
--- STATUS ---  worker 已自行 close()
```

（`!! worker 未启动` 是「终止 Worker」在 worker 已自行 `close()` 后的预期提示。）

**一条被这次复验修正的文档说法。** 之前 README 写的是"两侧都没有未捕获异常"，实测并不准确：
宿主页的 `#err-host` 探针在「触发 worker 未捕获错误」这一步**会**收到同一条 `TypeError`。做了对照
实验确认归因——把这一步从点击序列里去掉，`#err-host` 全程为空；加回来就出现，且只在那一刻出现。
所以正确说法是"除刻意报错那一步外全程为空"，README 已按此改写。

> 复验用的驱动脚本是临时的（放在 `target/` 下，随 `clean` 消失）：它把点击序列追加到
> **构建产物的副本**上，用 `--dump-dom` 取回结果。DevMode 那条路线不能这样截图——每次新页面加载都会
> 触发 Super Dev Mode 的重编译遮罩（"Compiling demohost"），截图会拍到遮罩而不是页面；DOM 转储不受
> 影响，因为它在遮罩出现前就已经跑完了整段点击序列。README 里引用的 `demo/screenshot.png` 取自
> 链接构建，UI 未随包名变更，仍然有效。

---

## 7. JSNI 清零（3.0.0 已完成）

3.0.0 的目标是"项目里不再有 JSNI"。盘点结果是 JSNI 只集中在
`client/MessagePort.java` 一个文件、共 8 处（`close` / `postMessage`×4 / `setOnMessage` / `start` /
`terminate`）；`verify/` 下已经是 0 处。难点不在数量，而在它被 §5.1 的泛型上界卡住。

### 7.1 实际做法

1. **引入 elemental2**：`pom.xml` 加 `com.google.elemental2:elemental2-core:1.2.3`（`compile`），
   `WebWorker.gwt.xml` 加 `<inherits name="elemental2.core.Core"/>`。
   选它而不是自定义一个无上界的数组类型，是因为 `elemental2.core.Transferable` 就是规范里
   `sequence<Transferable>` 的那个标记接口，`elemental2-dom` 的 `MessagePort` 也正是这么写的
   —— 直接对齐参考实现，避免自造一套。
2. **传输列表换类型**：`com.google.gwt.core.client.JsArray<MessagePort>` →
   `elemental2.core.JsArray<Transferable>`，并在旁边补 `Transferable[]` 的 `@JsOverlay` 便利重载
   （`Js.<JsArray<Transferable>>uncheckedCast(transfer)`，零开销：GWT 的 Java 数组本来就是 JS 数组）。
3. **`MessagePort` 转 JsInterop**：去掉 `extends JavaScriptObject`，改为
   `@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "MessagePort") implements Transferable`，
   8 处 JSNI body 全部删除（native JsType 的方法体由 GWT 生成，不再需要 JSNI）。
   `setOnMessage` 保持走 `HandlerDispatch`，与 `Worker` / `DedicatedWorkerGlobalScope` 一致。
4. **连带签名同步**：`Worker`、`DedicatedWorkerGlobalScope`、`DedicatedWorkerEntryPoint` 的
   `postMessage` 传输列表参数，以及 `MessageEvent.getPorts()` 的返回类型。
5. **模块版本 2.0.0 → 3.0.0**：传输列表签名变更是源码不兼容的（泛型不变性会让旧调用点编译失败）。

### 7.2 最终 `postMessage` 重载形状

四个位置（`MessagePort` / `Worker` / `DedicatedWorkerGlobalScope` / `DedicatedWorkerEntryPoint`）
提供同一组形状：

| 重载 | 种类 | 为什么是这个形状 |
|---|---|---|
| `postMessage(double)` | native | 保留旧 API；保持 native 是为了**避免装箱**——若只留 `Object` 版本，`double` 会先变成 `java.lang.Double` 才进入 JS |
| `postMessage(String)` | native | 保留旧 API |
| `postMessage(Object)` | native | 对应规范的 `any message`，新增 |
| `postMessage(double, JsArray<Transferable>)` | native | 旧 `(double, JsArray<MessagePort>)` 的直接替代；同样为避免装箱 |
| `postMessage(Object, JsArray<Transferable>)` | native | 对应规范的 `(any, sequence<Transferable>)` |
| `postMessage(double, Transferable[])` | `@JsOverlay` | Java 数组便利重载 |
| `postMessage(Object, Transferable[])` | `@JsOverlay` | Java 数组便利重载 |

**故意不提供** `(String, JsArray<Transferable>)` / `(String, Transferable[])` 的独立重载：Java 的
`String` 在 JS 里本来就是字符串，走 `Object` 版本不会有任何转换；`double` 才有装箱问题，所以只有它
需要单独的重载。这解释了这组重载看起来"不对称"的原因。

重载在 native JsType 内合法：`JsInteropRestrictionChecker#checkInstanceNameConsistency` 对
`member.isJsNative()` 提前返回，因此多个 `postMessage` 映射到同一个 JS 名不会报冲突
（`elemental2-dom` 的 `Worker` 有 6 个 `postMessage` 重载，同理）。

### 7.3 迁移方需要做的事

若下游代码用到过传输列表，只需两处机械替换：

```java
// 之前
JsArray<MessagePort> ports = JavaScriptObject.createArray().cast();
port.postMessage("hi", ports);

// 之后（任选其一）
worker.postMessage("hi", new MessagePort[] {port});          // Java 数组便利重载
port.postMessage("hi", JsArray.of(port));                    // elemental2 JsArray
```

`MessageEvent.getPorts()` 的接收方把 `import com.google.gwt.core.client.JsArray` 换成
`import elemental2.core.JsArray` 即可，`getAt(i)` / `getLength()` 可用。

---

## 8. 本次改动的文件清单

2.0.0（GWT 2.13.1 迁移）、3.0.0（JSNI 清零）与 4.0.0（包名重构）合并列出，"版本"列标注是哪一轮
引入的。4.0.0 那一轮是**纯重命名**：文件内容除了包名/模块名/资源路径之外没有其它改动，所以下表
不为 28 个被改文件逐个列行，只在最后集中说明。

| 文件 | 改动 | 版本 |
|---|---|---|
| `pom.xml` | 重写：GWT 2.13.1 / `org.gwtproject` BOM / Java 11 / net.ltgt 插件 / `release` 与 `gwt-verify` profile / 删除 OSSRH 与 wagon 配置 | 2.0.0 |
| `pom.xml` | 新增 `com.google.elemental2:elemental2-core:1.2.3`（compile）；版本 2.0.0 → 3.0.0 | 3.0.0 |
| `src/main/resources/.../WebWorker.gwt.xml` | 删除 DTD；新增 `useragent.UserAgent` 继承 | 2.0.0 |
| `src/main/resources/.../WebWorker.gwt.xml` | 新增 `elemental2.core.Core` 继承 | 3.0.0 |
| `.../client/AbstractWorker.java` | → JsInterop | 2.0.0 |
| `.../client/Worker.java` | → JsInterop | 2.0.0 |
| `.../client/Worker.java` | `postMessage` 传输列表 → `JsArray<Transferable>` + `Transferable[]` 便利重载；新增 `Object` 重载 | 3.0.0 |
| `.../client/WorkerGlobalScope.java` | → JsInterop（`importScripts` 仍用 GWT `JsArrayString`，见下） | 2.0.0 |
| `.../client/DedicatedWorkerGlobalScope.java` | → JsInterop | 2.0.0 |
| `.../client/DedicatedWorkerGlobalScope.java` | `postMessage` 传输列表同步换型 | 3.0.0 |
| `.../client/WorkerLocation.java` | → JsInterop | 2.0.0 |
| `.../client/MessageEvent.java` | → JsInterop | 2.0.0 |
| `.../client/MessageEvent.java` | `getPorts()` 返回 `elemental2.core.JsArray<MessagePort>`；补 `getSource()` 缺陷说明 | 3.0.0 |
| `.../client/ErrorEvent.java` | → JsInterop | 2.0.0 |
| `.../client/MessagePort.java` | **修复 `setOnMessage` 缺陷**；补充泛型约束说明（当时仍为 JSO+JSNI） | 2.0.0 |
| `.../client/MessagePort.java` | **去掉全部 8 处 JSNI**，改为 JsInterop native JsType `implements Transferable` | 3.0.0 |
| `.../client/MessageHandler.java` / `ErrorHandler.java` | → `@JsFunction` | 2.0.0 |
| `.../client/HandlerDispatch.java` | 新增（包级私有） | 2.0.0 |
| `.../client/Json.java` | 新增（包级私有） | 2.0.0 |
| `.../client/JsGlobal.java` | 新增（包级私有） | 2.0.0 |
| `.../client/DedicatedWorkerEntryPoint.java` | 不变 | 2.0.0 |
| `.../client/DedicatedWorkerEntryPoint.java` | `postMessage` 传输列表同步换型；补 `terminate()` 缺陷说明 | 3.0.0 |
| `.../client/GwtWebWorker.java` | 不变 | — |
| `.../linker/DedicatedWorkerLinker.java` | 不变 | — |
| `.../linker/DedicatedWorkerTemplate.js` | 不变（契约仍然成立） | — |
| `verify/` | 新增：harness 两个模块 + host 页面 + `run-e2e.py` | 2.0.0 |
| `verify/worker/.../WorkerSmoke.java` | `ports` 字段换型，新增 `portArray`，覆盖全部新重载 | 3.0.0 |
| `verify/host/.../HostSmoke.java` | 新增 `MessageChannel` 绑定与端口转移往返断言（第 11 项检查） | 3.0.0 |
| `pom.xml` | 新增 `gwt-demo` profile（`build-classpath` → `copy-resources` → 手工 `exec` 调 GWT 编译器） | demo |
| `demo/host/.../DemoHost.java` | 新增：`EntryPoint` + 11 个按钮的页面；`isJsNumber()` 做消息类型判定（§5.9） | demo |
| `demo/host/.../DemoHost.gwt.xml` | 新增：`<entry-point>`、不 pin `user.agent`（主线程要全排列） | demo |
| `demo/worker/.../DemoWorker.java` | 新增：`extends DedicatedWorkerEntryPoint`，处理 echo/number/json/location/boom/port/close | demo |
| `demo/worker/.../DemoWorker.gwt.xml` | 新增：`dedicatedworker` linker、pin `user.agent=safari`、`<entry-point>` | demo |
| `demo/web/index.html` | 新增：挂载点 + `#err-host` 未捕获异常探针 + 暗色模式样式 | demo |
| `demo/serve.py` | 新增：本地 http 服务（worker 不能从 `file://` 加载），钉 `.js` → `text/javascript` | demo |
| `pom.xml` | 新增 `gwt-demo-devmode` profile：GWT 自带 DevMode（Super Dev Mode），宿主模块实时重编译 | devmode |
| `demo/screenshot.png` | 新增：点完全部按钮后的页面截图，README 引用 | demo |
| `README.md` | 重写：新坐标、集成片段、验证方法、互操作模型、坑位清单、手动 demo 章节（DevMode + 链接构建两条路） | 2.0.0 / 3.0.0 / demo |
| `MIGRATION-GWT-2.13.md` | 本文档 | 2.0.0 / 3.0.0 / demo |

### 8.1 4.0.0 包名重构涉及的文件

目录级移动（`git mv` 语义，共 6 处）：

```
src/main/java/com/google/gwt/webworker            → src/main/java/org/eostep/gwt/webworker
src/main/resources/com/google/gwt/webworker       → src/main/resources/org/eostep/gwt/webworker
demo/host/java/com/google/gwt/webworker           → demo/host/java/org/eostep/gwt/webworker
demo/worker/java/com/google/gwt/webworker         → demo/worker/java/org/eostep/gwt/webworker
verify/host/java/com/google/gwt/webworker         → verify/host/java/org/eostep/gwt/webworker
verify/worker/java/com/google/gwt/webworker       → verify/worker/java/org/eostep/gwt/webworker
```

随后删掉变空的 `com/google/gwt`、`com/google`、`com` 目录链。文件内容改动共 **28 个文件**：

| 文件（组） | 改动 | 版本 |
|---|---|---|
| `pom.xml` | 6 处 dotted 引用：`gwt.module` / `gwt.verify.hostModule` / `gwt.verify.workerModule` / `gwt.demo.hostModule` / `gwt.demo.workerModule` | 4.0.0 |
| `src/main/java/.../client/*.java`（12 个类） | 各 1 处 `package` 声明 | 4.0.0 |
| `.../linker/DedicatedWorkerLinker.java` | `package` 声明 + 模板资源路径 `org/eostep/gwt/webworker/linker/DedicatedWorkerTemplate.js` | 4.0.0 |
| `src/main/resources/.../WebWorker.gwt.xml` | 模块 `<inherits>`、`<define-linker class=…>`、`<entry-point class=…>`；**`com.google.gwt.core.Core` 与 `com.google.gwt.useragent.UserAgent` 两行保持不动** | 4.0.0 |
| `demo/host/.../DemoHost.gwt.xml` + `DemoHost.java` | 包声明与 `<inherits>` | 4.0.0 |
| `demo/worker/.../DemoWorker.gwt.xml` + `DemoWorker.java` | 包声明与 `<inherits>` | 4.0.0 |
| `verify/host/.../HostSmoke.gwt.xml` + `HostSmoke.java` | 包声明与 `<inherits>` | 4.0.0 |
| `verify/worker/.../WorkerSmoke.gwt.xml` + `WorkerSmoke.java` | 包声明与 `<inherits>` | 4.0.0 |
| `README.md` / `MIGRATION-GWT-2.13.md` | 包名、模块名、集成片段、版本号 | 4.0.0 |

核对手段（改完后的全仓扫描）：

```
grep -rn 'com\.google\.gwt\.webworker\|com/google/gwt/webworker'   → 0 命中
grep -rno 'com\.google\.gwt\.[A-Za-z0-9_]*' | 分词统计              → 只剩 core(36) / user(14) / useragent(7)
                                                                      / dev(7) / dom(6) / http(1)
```

即：本项目的旧包名已彻底消失，GWT 自身的包引用一个都没被误改。

**一处跨行残留值得记下来**：`WebWorker.gwt.xml` 里原本有一行是
`<inherits name="com.google.gwt.` + 换行 + `webworker.WebWorker" />`，按行的字符串替换抓不到它，
是手工修掉的。做这类批量重命名时，**跨行断开的引用必须单独 grep 一遍**（用多行模式匹配
`com\.google\.gwt\.\s*webworker`）。

### 8.2 有意未动的部分

- **`WorkerGlobalScope.importScripts(JsArrayString)`**：`JsArrayString` 是 GWT 自带的
  `JavaScriptObject` 子类（GWT 内部给它的每个方法都写了 JSNI body），出现在**我们的**签名里，但
  它本身不是我们写的 JSNI。改它是一次纯粹的 API 破坏，与"消除本项目的 JSNI"无关，故保留。
  若将来要统一到 elemental2，对应类型是 `elemental2.core.JsArray<String>`。
- **`MessageEvent.getDataAsJSO()` 返回 `com.google.gwt.core.client.JavaScriptObject`**：返回 JSO
  类型本身不是 JSNI（实现是 JsInterop 的 `Json.parse`），保留以维持源码兼容。
- **`MessagePort.terminate()` / `DedicatedWorkerGlobalScope.terminate()` / `MessageEvent.getSource()`**：
  见 §5.8。
