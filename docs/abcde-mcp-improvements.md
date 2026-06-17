# abcde MCP 工具改进规划文档

> 作者：Claude Code（基于对 `hish-unsigned.hap` 的 ArkTS 层分析实践）  
> 日期：2026-06-17  
> 适用范围：abcde MCP（HarmonyOS ArkTS ABC 反编译/分析 MCP Server）

---

## 1. 背景与目标

### 1.1 本次分析场景

在对 `dev.hackeris.hish`（一个基于 QEMU 的虚拟机管理器 HAP）进行 ArkTS 层安全审计时，全程使用 abcde MCP 工具完成：

- HAP 基本信息提取（manifest、资源、ABC 文件）
- ABC 字符串搜索（关键词、正则）
- 类/方法反编译
- 交叉引用查询
- 资源搜索与手动提取

### 1.2 文档目标

本文档记录使用过程中发现的**可改进点**，按优先级和实现成本排序，供后续迭代参考。目标是将 abcde MCP 从“可用”提升至“HarmonyOS 逆向首选工具”。

---

## 2. 当前优势（保持项）

| 能力 | 评价 |
|------|------|
| `open_hap` / `get_hap_manifest` | 一键解析 HAP 结构，快速了解 bundle、ability、权限、ABC 文件 |
| `search_strings` | 正则搜索 ABC 字符串常量，定位关键词效率高 |
| `decompile_class` / `decompile_method` | 能快速拿到方法级伪代码，适合逐函数审计 |
| `search_resources` | 支持按类型/名称搜索 HAP 资源 |
| `get_xrefs_to_*` | 提供基础的交叉引用能力 |
| 与 MCP 生态集成 | 可直接被 Claude Code / 其他 Agent 调用，形成自动化分析流水线 |

---

## 3. 改进项详单

### 3.1 反编译可读性提升（P0 - 最高优先级）

#### 问题描述

大量方法在结构恢复失败后会 fallback 到 linear block 模式，输出中存在以下现象：

- `_acc_` 变量链冗余，打断数据流阅读
- async/try-catch 结构被展开为异常驱动的 `throw _acc_` 块
- 常量字符串拼接被截断，例如 `runJavaScript("))")` 明显是片段
- 某些方法出现 `.data.data.data...` 无限链式访问

**实际案例（来自本次分析）：**

```js
// WebTerminal.sendInput 反编译片段
_acc_ = _acc_.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data.data;
```

```js
// WebTerminal.load / flushData 中
v10 = this.webviewController;
v11 = "))";
_acc_ = v2.webviewController.runJavaScript(v11);
```

#### 建议方案

1. **增强控制流恢复算法**
   - 对常见 ArkTS 编译模式（async/await、try-catch、三元表达式、可选链）做专项还原。
   - 引入 SSA 形式或伪寄存器染色，减少 `_acc_` 泛滥。

2. **字符串拼接还原**
   - 识别 `runJavaScript`、`hilog.info` 等常见 API 的字符串构建模式，将片段重组成完整表达式。

3. **双视图输出**
   - 当高级反编译失败时，除了 linear block，再提供一份**原始字节码/汇编级 IR** 视图，方便高级用户手动还原。

4. **类型标注保留**
   - 尽量从 ABC 的 type info / 调试信息中恢复 TypeScript 类型，提升可读性。

#### 预期收益

- 单函数审计时间减少 50% 以上。
- 降低因反编译失真导致的误报/漏报。

---

### 3.2 批量反编译与项目导出（P0）

#### 问题描述

目前只能逐个类/方法反编译。本次分析涉及 53 个类、2000+ 个方法，反复调用 `decompile_class` 非常碎片化，难以做全局 grep 和交叉文件分析。

#### 建议方案

1. **新增 `export_project` 工具**
   - 输入：ABC 文件路径、输出目录
   - 输出：按原包结构组织的 `.ts` / `.js` 文件树
   - 可选：同时导出字符串表、资源索引、类层次结构 JSON

2. **新增 `decompile_classes` 批量接口**
   - 支持传入类名数组，一次性返回多个类的反编译结果。
   - 或者支持 `decompile_package(package_name)` 按包批量反编译。

3. **输出到 MCP 工作区**
   - 导出结果可直接写入当前 Claude Code 工作区，方便 Agent 用 `Read`/`Grep` 继续分析。

#### 预期收益

- 支持“先全局鸟瞰，再定点深挖”的分析模式。
- 便于与现有代码搜索工具、IDE、版本控制结合。

---

### 3.3 跨模块/跨文件交叉引用增强（P0）

#### 问题描述

本次分析中查询 `SimpleWebView`、`GuideWebPage`、`startVm.startVm` 的 xrefs 时，只返回了类自身的 `func_main_0`，未显示真正的调用方（如 `Index`、`SharedFolderContent`）。

#### 建议方案

1. **修复跨 class 的 xref 解析**
   - 当前可能只解析了同一模块内的直接引用，需扩展到 import/use 边界。
   - 对 `@normalized:N&&&entry/src/main/ets/components/SimpleWebView&` 这类规范化 import 做专门处理。

2. **新增调用图工具**
   - `get_callers(class, method)`：返回所有调用该方法的位置（类名、方法名、行号/偏移）。
   - `get_callees(class, method)`：返回该方法调用的所有方法。
   - `build_call_graph(seed_classes)`：生成以指定类为起点的调用子图（JSON/Graphviz）。

3. **字段/字符串引用追踪**
   - `get_xrefs_to_string(pattern)`：查找某个字符串常量被哪些方法引用。
   - `get_xrefs_to_resource(name)`：查找资源被哪些方法使用。

#### 预期收益

- 快速定位某个组件、某个危险 API 的所有使用点。
- 支撑数据流分析和攻击面梳理。

---

### 3.4 数据流追踪与安全分析模式（P1）

#### 问题描述

安全审计最耗时的是追踪“外部输入 → 危险 sink”的完整路径。例如：

- `GuideWebPage` 的 `page` 参数是否流向 WebView `src`？
- `startVm` 中用户配置的字段是否流向命令行参数？
- `QemuAgent.exec` 的参数是否可被外部控制？

目前需要人工逐方法跳转。

#### 建议方案

1. **新增 `trace_dataflow` 工具**
   - 参数：起点（类/方法/参数）、终点模式（如 `runJavaScript`、`exec`、`startAbility`、`fs.unlinkSync`）
   - 输出：调用链列表，每条链包含经过的类/方法/关键语句

2. **内置常见 Source/Sink 识别**

   | Source 类型 | Sink 类型 |
   |-------------|-----------|
   | `getRouter().getParams()` | `WebView.src`、`runJavaScript` |
   | `want` / `launchParam` | `startAbility` |
   | `pasteboard.getData()` | `fs.write`、`fs.unlink` |
   | 用户输入/配置字段 | `QemuAgent.exec`、`socket.send` |
   | 文件路径/URL 参数 | 动态 import、eval、Function |

3. **污点分析（长期）**
   - 在反编译 IR 上实现基础污点传播，标记从 source 到 sink 的完整数据依赖。

#### 预期收益

- 将“人工跟路径”转化为“工具自动枚举”，大幅提升漏洞挖掘效率。
- 降低对分析者经验的依赖。

---

### 3.5 HAP 资源直接读取（P1）

#### 问题描述

`search_resources` 只能列出资源路径，无法直接读取内容。本次分析中为了查看 `term.js`、`guide/*.html`，需要手动执行 `unzip` 提取。

#### 建议方案

1. **新增 `read_resource` 工具**
   - 输入：HAP 路径 + 资源路径（如 `resources/rawfile/term/term.js`）
   - 输出：文本内容（对 html/js/css/md）或 base64（对图片/二进制）

2. **资源类型智能处理**
   - 文本资源默认 UTF-8 解码。
   - 图片返回元数据（尺寸、类型）+ base64 可选。
   - 大型资源（如 qcow2）支持只读前 N 字节或返回大小信息。

3. **与字符串/资源引用联动**
   - 反编译中遇到 `$rawfile('term/term.html')` 时，可直接提供跳转/读取入口。

#### 预期收益

- 形成 HAP 分析闭环：静态代码 + 资源内容一体查看。
- 便于审计 WebView 加载的 HTML/JS 是否存在前端漏洞或硬编码。

---

### 3.6 ADB / 动态分析联动（P1，若规划 adb MCP）

#### 问题描述

当前工具是纯静态分析。对于 HAP 这类需要运行时行为验证的目标，缺少与设备/模拟器的联动。

#### 建议方案

1. **adb MCP 基础能力**
   - `adb_install(hap_path)`：安装 HAP
   - `adb_shell(command)`：执行 shell 命令
   - `adb_logcat(filter, lines)`：抓取日志
   - `adb_pull(remote, local)` / `adb_push(local, remote)`：文件传输
   - `adb_start_activity(bundle, ability, params)`：带参数启动 ability

2. **动静结合分析**
   - 静态定位到某个 ability 后，一键 `adb_start_activity` 触发并观察日志。
   - 静态发现可疑文件路径后，自动 `adb_pull` 到本地检查。
   - 运行时 hook 配合：通过 frida/objection  hook 某个方法，结合静态反编译的符号信息定位。

3. **组件可达性扫描**
   - 扫描所有 `exported=true` 的 ability/service/receiver。
   - 自动生成可启动的 `want` 参数模板，方便 fuzz 测试 deeplink/外部调用。

#### 预期收益

- 从“静态猜测”升级为“动静验证”。
- 对导出组件、权限绕过、Intent 注入等漏洞类型特别有效。

---

### 3.7 字符串搜索增强（P2）

#### 问题描述

`search_strings` 返回结果只有字符串本身和所在位置标识，缺少上下文。

#### 建议方案

1. **增加上下文信息**
   - 返回字符串所在的方法名、类名。
   - 可选返回前后若干条指令或源码行。

2. **支持分类搜索**
   - `search_strings_type(pattern, type)`：仅搜索特定类型的字符串（如 URL、类名、日志模板）。

3. **结果去重与聚类**
   - 对大量命中的结果按类/方法聚类，减少噪音。

#### 预期收益

- 快速判断某个关键词是否值得关注。
- 减少在海量结果中筛选的时间。

---

### 3.8 大方法分页与性能优化（P2）

#### 问题描述

部分大方法（如 `SharedFolderContent.setInitiallyProvidedValue`）提示 “method too large”，只返回部分代码，影响完整性。

#### 建议方案

1. **支持 offset/limit 分页**
   - `decompile_method(class, method, offset=0, limit=500)` 完整翻阅大方法。

2. **流式/分块返回**
   - 对超大方法，分块返回，避免一次性输出过大。

3. **延迟反编译**
   - `list_methods` 可先返回方法元数据（大小、字符串常量数），用户再选择重点反编译。

#### 预期收益

- 不再遗漏大方法中的关键逻辑。
- 提升大 ABC 文件的分析体验。

---

### 3.9 混淆与符号还原（P2 - 长期）

#### 问题描述

部分 HAP 可能开启 ArkCompiler 混淆，类名/方法名被压缩。本次目标未明显混淆，但未来会遇到。

#### 建议方案

1. **解析混淆映射**
   - 如果 HAP 内包含 `sourceMaps` 或 `obfuscation.json`，自动读取并建立 `混淆名 → 原始名` 映射。

2. **支持手动重命名**
   - 类似 JADX 的 `rename_class` / `rename_method`，允许分析者给匿名/混淆符号打标记。

3. **显示行号信息**
   - 利用 ABC 中的 debug info 恢复原始 `.ets` 行号，便于与源码对照。

#### 预期收益

- 提升混淆 HAP 的可读性。
- 支持团队协作和报告撰写。

---

## 4. 实现路线图建议

### 第一阶段：核心体验（1-2 周）

- [ ] 修复反编译 fallback 质量（优先解决 `_acc_` 链、字符串拼接截断）
- [ ] 新增 `export_project` 批量导出
- [ ] 修复 `get_xrefs_to_class/method` 跨模块引用

### 第二阶段：分析效率（2-4 周）

- [ ] 新增 `read_resource` 直接读取 HAP 资源
- [ ] 新增 `trace_dataflow` 基础数据流追踪
- [ ] 大方法分页支持
- [ ] 字符串搜索增加上下文

### 第三阶段：动态与高级能力（1-2 月）

- [ ] adb MCP 基础能力（安装、日志、shell、activity 启动）
- [ ] 调用图生成
- [ ] 混淆符号还原
- [ ] 污点分析原型

---

## 5. 成功指标

| 指标 | 当前状态 | 目标 |
|------|---------|------|
| 单个类反编译平均可读性 | 中低（大量 fallback） | 高（自然控制流） |
| 全局代码搜索能力 | 无（只能单类反编译后搜索） | 支持项目级导出 + grep |
| 跨文件调用追踪 | 弱（xref 不完整） | 完整 caller/callee 图 |
| HAP 资源查看 | 需手动 unzip | 一键读取 |
| 动静结合验证 | 不支持 | adb 联动 |
| 数据流审计 | 全人工 | 半自动化 trace |

---

## 6. 附录：本次分析中产生的具体需求用例

以下用例来自对 `hish-unsigned.hap` 的实际审计需求，可作为功能验收场景：

1. **WebView rawfile 路径穿越审计**
   - 追踪 `GuideWebPage.page` 从 `getRouter().getParams()` 到 `WebView.src` 的完整路径。
   - 验证是否存在外部输入可达该参数。

2. **QEMU 命令注入审计**
   - 识别 `startVm.startVm` 中所有从配置流入命令行参数的字段。
   - 检查这些字段是否经过过滤/转义。

3. **QGA 任意命令执行审计**
   - 定位 `QemuAgent.exec` 和 `execAndGetOutput` 的所有调用点。
   - 判断调用参数是否可被外部控制。

4. **共享文件夹路径穿越审计**
   - 追踪 `SharedFolderContent.current`、`tempFolderName`、`files` 等路径数组。
   - 检查最终文件操作是否限制在共享目录内。

5. **WebView JS Bridge 攻击面审计**
   - 导出并读取 `term.js`、`guide/*.html`。
   - 检查 `native.*` 暴露的方法是否有过度授权或可被恶意页面利用。

---

## 7. 结语

abcde MCP 已经具备了 HarmonyOS HAP 静态分析的核心能力，是逆向工作中非常实用的工具。下一步的关键是**提升反编译质量**和**增强全局分析能力**，让分析者从“逐个方法阅读”转向“项目级关联分析”。结合 adb 动态分析后，将形成完整的 HarmonyOS 应用安全审计工具链。
