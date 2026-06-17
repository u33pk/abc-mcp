# abcde MCP 工具使用反馈与改进建议报告

| 项目 | 内容 |
|---|---|
| 反馈人 | Claude Code（基于实际使用体验） |
| 分析样本 | `Melotopia-1.10.3_HiCar_unsigned.hap` |
| 反馈日期 | 2026-06-17 |
| 工具版本 | abcde MCP（当前会话可用版本） |

---

## 1. 总体评价

abcde MCP 是目前少有的面向 OpenHarmony/HarmonyOS ArkTS 字节码（ABC）的静态分析工具，能够完成 HAP 解析、类列举、方法反编译、字符串搜索、资源分析等核心任务。在本次对 `Melotopia-1.10.3_HiCar_unsigned.hap` 的安全审计中，工具成功支撑了 WebView 使用分析、硬编码密钥发现、原生库识别等关键工作，**整体可用性较高**。

但在实际使用过程中，仍存在一些影响效率的 friction，主要集中在：**类名格式不一致、反编译控制流还原能力、方法可读性、安全分析聚合能力、原生库整合、清单信息完整性**等方面。下文将按优先级给出具体问题与改进建议。

---

## 2. 高优先级改进项

### 2.1 类名输入格式不统一

#### 问题描述

同一个类在不同接口中需要使用不同的名称格式，造成重复尝试和困惑：

| 接口 | 可接受的类名示例 |
|---|---|
| `reconstruct_class` | `WebPage`（短名） |
| `get_class_detail` | `&entry/src/main/ets/pages/WebPage&`（完整名） |
| `decompile_class` | `&entry/src/main/ets/pages/WebPage&`（完整名） |
| `decompile_method` | `&entry/src/main/ets/pages/WebPage&`（完整名） |

实际使用中的错误路径：

```
> reconstruct_class(class_name="WebPage")          ✅ 成功
> decompile_class(class_name="WebPage")            ❌ Class not found: WebPage
> get_class_detail(class_name="WebPage")           ❌ Class not found: WebPage
> decompile_class(class_name="&entry/.../WebPage&") ✅ 成功
```

#### 影响

- 增加调用失败率，用户需要反复查询 `list_classes` 复制带 `&` 的完整名；
- 对初学者不友好，错误信息没有给出正确格式提示；
- 脚本化批量分析时，必须维护两套类名映射。

#### 改进建议

1. **所有接口统一接受两种格式**，内部自动归一化：
   - 短名：`WebPage`
   - 完整名：`&entry/src/main/ets/pages/WebPage&`
2. `list_classes` 输出时同时提供 `shortName` 和 `fullName` 字段；
3. `Class not found` 错误中增加提示：
   ```
   Class not found: WebPage. Did you mean "&entry/src/main/ets/pages/WebPage&"?
   Use list_classes to see available class names.
   ```

---

### 2.2 反编译控制流重建能力较弱

#### 问题描述

大量方法在反编译时触发：

```
Structure analysis failed: Cannot reduce the graph for region: region_0
Falling back to linear block decompilation
```

导致关键业务逻辑还原不完整。例如 `AiAnalyzePage.analyzeMusicPreference` 反编译结果中只能看到：

```javascript
v15.nowProgress = "正在整理用户数据...";
v16 = 0;
v17 = 1;
_acc_ = getPlayLists;
_acc_ = this._acc_(v16, v17);
_acc_ = await _acc_;
_acc_ = false;
```

而真实的 DeepSeek API 调用、请求头构造、JSON 解析、错误处理等逻辑在伪代码中几乎不可见，只能退回到字符串搜索。

#### 影响

- 复杂方法（async、try-catch、状态机）无法通过伪代码直接理解；
- 安全审计中大量时间花在手工反汇编跟指令上；
- 报告产出效率降低，关键证据难以从反编译结果中截图引用。

#### 改进建议

1. **增强对 async/await 状态机的识别**：ArkTS async 函数通常编译为状态机，识别 `__awaiter` / `generator` 模式后，可还原为接近源码的 `await` 顺序代码；
2. **改进 try-catch 控制流还原**：当前 catch handler 常以异常驱动入口形式出现，建议将其与对应 try 块关联，生成 `try { ... } catch (e) { ... }` 结构；
3. **结构分析失败时保留更多辅助信息**：
   - 输出原始字节码/反汇编片段；
   - 给出寄存器-变量映射注释；
   - 标注每个基本块的入口和出口。

---

### 2.3 方法名可读性差，定位困难

#### 问题描述

ABC 中大量方法名为编译器生成的匿名名或编码名：

- `anon_1acb4100`
- `anon_2664859e`
- `#~@0>@1***^3*#^1`
- `#13617913633325172968#`

当分析 WebView 配置时，虽然通过字符串搜索知道 `domStorageAccess`、`javaScriptAccess` 出现在某个方法中，但无法直观判断该方法对应源码中的哪个组件回调或生命周期函数。

#### 影响

- 难以建立反编译结果与源代码语义之间的映射；
- 写报告时引用证据困难（只能引用 `anon_1acb4100`）；
- 自动化分析时难以按语义过滤方法。

#### 改进建议

1. **启发式方法重命名**：根据方法体内调用的关键 API 自动推断语义名：
   - 发现 `ldobjbyname "onControllerAttached"` 后调用的 `definefunc` → 命名为 `onControllerAttached_callback`；
   - 发现方法内大量 Web 组件属性调用 → 命名为 `buildWebComponent`；
   - 发现 `axios.request` / `http.createHttp().request` → 命名为 `sendApiRequest`。
2. **在 `get_class_detail` 方法列表中附加“关键引用”摘要**：例如每个方法后面列出其引用的前 5 个独特 API 名或字符串，方便快速判断；
3. **保留原始名作为别名**：避免不同会话间名称不一致。

---

## 3. 中优先级改进项

### 3.1 缺少安全分析聚合接口

#### 问题描述

目前做一次完整的安全审计需要手动组合多次 `search_strings` 和 `decompile_method`。例如查找硬编码密钥需要搜索 `api_key`、`sk-`、`Authorization`、`Bearer` 等多个模式；查找 WebView 风险需要搜索 `webview`、`registerJavaScriptProxy`、`fileAccess` 等。

#### 影响

- 重复劳动多，分析速度慢；
- 容易遗漏模式（例如本次就差点漏掉 `API_KEY` 变量名）；
- 不同使用者标准不一致，报告质量参差。

#### 改进建议

新增一组高层安全扫描接口：

| 接口 | 功能 |
|---|---|
| `security_scan_keys` | 扫描 API Key、Token、密码、私钥、Secret、Bearer Token 等 |
| `security_scan_webview` | 找出所有 WebView 使用位置、配置项（JS/fileAccess/databaseAccess/domStorageAccess）、是否注册 JS Bridge |
| `security_scan_network` | 找出所有 HTTP/HTTPS 端点、`@ohos:net.http` / axios / fetch 调用、SSRF 风险点 |
| `security_scan_permissions` | 对比 `module.json` 声明权限与实际代码使用，识别过度授权 |
| `security_scan_debug` | 检测 `module.json` debug 标志、硬编码日志开关、调试域名等 |

每个扫描接口返回结构化 JSON，包含：位置、风险等级、证据字符串、修复建议。

---

### 3.2 `get_hap_manifest` 输出不完整

#### 问题描述

`get_hap_manifest` 当前仅返回 app/module 的基本字段，缺少安全审计最关心的内容：

- `requestPermissions`
- `abilities` 的 `exported`、`skills`、`uris`
- `extensionAbilities`
- `debug` / `buildMode`

实际分析中不得不手动执行：

```bash
unzip -p Melotopia-1.10.3_HiCar_unsigned.hap module.json
```

才能拿到完整清单。

#### 改进建议

1. `get_hap_manifest` 返回完整 `module.json` 内容；
2. 或新增 `get_full_manifest` 接口；
3. 对关键安全字段做高亮/摘要，例如：
   ```json
   {
     "debug": true,
     "buildMode": "debug",
     "permissions": [...],
     "exported_abilities": [...]
   }
   ```

---

### 3.3 原生库（.so）分析未整合

#### 问题描述

分析 NAPI 模块时需要手动解压 HAP：

```bash
mkdir -p /tmp/melo_hap_extract
cd /tmp/melo_hap_extract
unzip -o Melotopia-1.10.3_HiCar_unsigned.hap 'libs/arm64-v8a/*.so'
nm -D liblrcparser.so | grep -iE 'napi|register|init'
```

工具已经能列出 HAP 内容，但没有开放原生库提取、符号导出、NAPI 注册分析能力。

#### 改进建议

1. `open_hap` 时自动提取 `.so` 文件并建立索引；
2. 新增接口：
   - `list_native_libs`：列出所有原生库；
   - `get_native_exports(lib_name)`：输出动态符号表；
   - `detect_napi_modules`：自动识别 `napi_module_register`、`RegisterXxxModule`，输出向 ArkTS 暴露的方法列表。
3. 将原生库暴露的方法与 ABC 中的 import 语句关联，回答“这个 NAPI 模块在哪些 ArkTS 类里被调用”。

---

### 3.4 字符串搜索结果缺少上下文

#### 问题描述

`search_strings` 返回格式为：

```
&entry/src/main/ets/pages/AiAnalyzePage&.#~@0=#AiAnalyzePage: "sk-807d258b9f2744438663c67f5c2f7d05"
```

只能知道字符串存在，但无法直接判断它是被赋值给变量、写入日志、还是拼接进 HTTP 头。

#### 改进建议

1. 搜索结果附加附近 3-5 行反汇编或伪代码；
2. 支持点击结果直接调用 `decompile_method`；
3. 增加 `search_strings_with_context` 接口，返回结构化数据：
   ```json
   {
     "class": "...",
     "method": "...",
     "string": "sk-...",
     "context": "..."
   }
   ```

---

## 4. 低优先级改进项

### 4.1 批量操作能力

#### 问题描述

当前想看多个类/方法时只能逐个调用。

#### 改进建议

1. `decompile_classes(["A", "B", "C"])` 批量反编译；
2. `extract_all_strings` / `extract_all_urls` 一次性导出；
3. 支持将分析结果导出为 JSON/CSV，方便脚本二次处理。

---

### 4.2 交叉引用（Xref）能力扩展

#### 问题描述

现有 `get_xrefs_to_method` / `get_xrefs_to_class` / `get_xrefs_to_field` 有限，实际更常用的是：

- 谁使用了某个字符串常量？
- 谁调用了某个 URL/API？
- 某个 WebView 配置被哪些页面引用？

#### 改进建议

新增 `get_xrefs_to_string` 或更通用的 `search_usages(query, type)`，支持对字符串、资源 ID、API 调用、导入模块的交叉引用。

---

### 4.3 资源引用自动解析

#### 问题描述

反编译结果中常见 `$string:app_name`、`$media:icon` 等引用，但工具没有自动解析其实际值。

#### 改进建议

1. 在反编译输出中自动内联资源值；
2. 或在 `search_strings` 结果中附带解析后的资源文本；
3. 增强 `resolve_resource` 的易用性，使其在常见分析流程中更自然地被调用。

---

## 5. 推荐的最小可行改进路线图

如果开发资源有限，建议按以下顺序迭代：

### Phase 1：减少基础摩擦（1-2 周）

- [ ] 统一类名输入格式（短名/完整名自动兼容）
- [ ] `Class not found` 错误提示正确格式
- [ ] `get_hap_manifest` 返回完整 `module.json`

### Phase 2：提升反编译可读性（2-4 周）

- [ ] 改进 async/await 状态机还原
- [ ] 改进 try-catch 控制流还原
- [ ] 启发式方法重命名（基于关键 API 调用）

### Phase 3：增强安全分析效率（2-4 周）

- [ ] `security_scan_keys`
- [ ] `security_scan_webview`
- [ ] `security_scan_network`
- [ ] `security_scan_permissions`

### Phase 4：原生库与批量分析（4-6 周）

- [ ] 自动提取与分析 `.so`
- [ ] `detect_napi_modules`
- [ ] 批量反编译与结果导出

---

## 6. 结论

abcde MCP 已经是一个可用的 HarmonyOS ArkTS 静态分析工具，但在**分析效率**和**报告产出**方面仍有较大提升空间。最影响当前使用体验的三个问题是：

1. **类名格式不统一** —— 导致反复试错；
2. **复杂控制流反编译质量不足** —— 导致关键逻辑看不清；
3. **缺少安全扫描聚合接口** —— 导致重复手工搜索。

如果能优先解决这三个问题，工具在专业安全审计和日常逆向分析中的可用性将显著提升。

期待 abcde MCP 的后续版本！
