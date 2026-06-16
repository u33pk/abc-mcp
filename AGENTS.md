<!-- From: /Users/vv/project/abc-mcp/AGENTS.md -->
# AGENTS.md - ABCDE MCP 项目指南

## 项目背景

本项目 **ABCDE MCP** 是基于 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 的 OpenHarmony 逆向工具链。

- **原项目作者**：Yricky
- **原仓库地址**：[https://github.com/Yricky/ABCDE](https://github.com/Yricky/ABCDE)
- **本项目定位**：在 ABCDE 基础上封装 **MCP（Model Context Protocol）服务**，为 LLM 提供鸿蒙应用的反编译与分析能力。

## 技术栈

- **语言**：Kotlin Multiplatform（核心为 JVM）
- **构建**：Gradle (Kotlin DSL)
- **Kotlin 版本**：2.4.0
- **Gradle 版本**：9.5.1

## 项目结构

```
modules/
├── common/          # 通用基础类型（LEByteBuf 等）
├── abcde/           # 核心 ABC 字节码解析、反汇编、反编译库
├── resde/           # 资源索引解析（.index 文件）
├── hapde/           # HAP 包解析（.hap 文件）
└── mcp/             # MCP 服务模块
```

### 核心模块 `modules/abcde`

```
src/commonMain/kotlin/me/yricky/oh/abcd/
├── AbcBuf.kt                    # ABC 文件解析入口
├── cfm/                         # 类/方法/字段定义
├── code/                        # 方法代码段
├── isa/                         # 指令集定义（从官方 isa.yaml 同步）
├── literal/                     # 字面量数组
└── decompiler/                  # 反编译器
    ├── behaviour/               # IR 中间表示（IrOp）
    ├── CodeSegment.kt           # CFG 基本块定义
    └── structure/               # 结构化分析
        ├── RegionGraph.kt       # 轻量级有向图
        ├── DominatorGraph.kt    # 支配树
        ├── LoopFinder.kt        # 循环检测
        ├── StructureAnalysis.kt # 结构化分析核心
        ├── StructuredDecompiler.kt # 统一入口
        ├── StructuredToJs.kt    # 代码生成器
        ├── IrOpOptimizer.kt     # 优化器（Pass 架构）
        ├── OptimizationPasses.kt # 优化 Pass 实现
        └── statement/           # 语句类型
```

## JDK 支持矩阵

| JDK 版本 | 状态 | 说明 |
|---------|------|------|
| **JDK 17 / 21** | ✅ 可运行 Gradle | 编译任务仍需 JDK 26 toolchain；请通过 `org.gradle.java.installations.paths` 配置本地 JDK 路径 |
| **JDK 26** | ✅ 推荐 | 项目默认 `jvmToolchain(26)`，与编译目标一致 |
| **JDK 27+** | ❌ 不支持 | Gradle 9.5 / Kotlin 2.4.0 尚未支持 |

> 当前配置：Gradle 9.5.1 + Kotlin 2.4.0 + Compose Multiplatform 1.11.1。

## 常用构建命令

```bash
# 启动 MCP 服务
./gradlew :modules:mcp:jvmRun

# 构建 MCP 可运行 fat jar（包含所有依赖）
./gradlew :modules:mcp:fatJar

# 发布到本地 Maven
./gradlew publishToMavenLocal
```

## 测试

```bash
# 运行所有反编译测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testAllAbcFiles"

# 运行单个文件测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testSpecificFile"

# 运行 MCP tool 测试
./gradlew :modules:mcp:jvmTest
```

测试文件路径（参考）：
- 源码：`/Users/vv/project/unitTest/`
- ABC 文件：`/Users/vv/project/unitTest/out/`
- HAP 文件：`/Users/vv/project/unitTest/kazumi/Kazumi_ohos_2.1.5_unsigned.hap`

## MCP 服务

### 模块位置
`modules/mcp/`

### 入口
`me.yricky.oh.mcp.MainKt`

### 暴露的 Tools（18 个）

#### ABC 字节码工具
| Tool | 功能 |
|------|------|
| `open_abc` | 打开 ABC 文件，返回基本信息 |
| `list_classes` | 列出所有类名，支持正则过滤 |
| `get_class_detail` | 获取类详情（父类、字段、方法列表） |
| `decompile_class` | 反编译指定类的所有方法 |
| `decompile_method` | 反编译指定方法 |
| `search_strings` | 搜索字符串常量（正则匹配） |
| `disassemble_method` | 获取方法的字节码反汇编 |
| `get_method_info` | 获取方法详情（参数名、行号、调试信息） |
| `get_xrefs_to_method` | 查找方法的调用者（交叉引用） |
| `get_xrefs_to_field` | 查找字段的读取者和写入者（交叉引用） |
| `get_xrefs_to_class` | 查找类的实例化位置与 `instanceof` 检查位置（交叉引用） |
| `get_class_hierarchy` | 查询类的父类、接口、子类与接口实现者 |
| `search_in_method` | 在方法体内按正则搜索反汇编文本，返回匹配行及上下文 |

#### HAP 包工具
| Tool | 功能 |
|------|------|
| `open_hap` | 解析 HAP 包，列出 ABC 文件和资源 |
| `get_hap_manifest` | 获取 module.json 清单内容 |
| `get_obfuscation_map` | 获取混淆映射 |

#### 资源工具
| Tool | 功能 |
|------|------|
| `search_resources` | 搜索资源（按名称、类型） |
| `resolve_resource` | 解析 `$string:app_name` 等资源引用 |

### fat jar 构建说明

`modules/mcp/build.gradle.kts` 中已添加 `fatJar` task，用于打包所有 runtime 依赖：

```kotlin
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations["jvmRuntimeClasspath"].map { if (it.isDirectory) it else zipTree(it) }
    })
    from({
        tasks["jvmJar"].outputs.files.singleFile.let { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    manifest {
        attributes["Main-Class"] = "me.yricky.oh.mcp.MainKt"
    }
}
```

输出位置：`modules/mcp/build/libs/mcp-*-fat.jar`

## 反编译器架构

### 已移植的核心组件（来自 xpanda）

xpanda 是另一个已停止维护的鸿蒙反编译器（Java 实现）。本项目移植了其核心算法：

1. **DominatorGraph** - 支配树计算（Cooper-Harvey-Kennedy 算法）
2. **LoopFinder** - 基于支配关系的循环检测
3. **StructureAnalysis** - 控制流规约算法（if/while/do-while/逻辑短路）
4. **RegionGraph** - 轻量级有向图（替代 Guava MutableValueGraph）

### 设计参考（来自 jadx）

[jadx](https://github.com/skylot/jadx) 是 Android 领域的反编译器。本项目的交叉引用（XRef）索引与缓存架构借鉴了 jadx 的 `UsageInfo` / `UsageInfoVisitor` / `IUsageInfoCache` 设计思想：

- 扫描一次建立“目标 → 引用源”反向索引
- 通过缓存接口抽象内存/持久化缓存
- 引用结果按类/方法/字段分类

底层字节码解析逻辑按 ABC 格式重新实现，未直接复用 jadx 代码。

### LLVM 风格优化 Pass

| Pass | 说明 | 状态 |
|------|------|------|
| `AlgebraicSimplificationPass` | 代数化简（常量折叠、恒等变换） | ✅ |
| `CopyPropagationPass` | 拷贝传播 | ✅ |
| `DeadCodeEliminationPass` | 死代码消除 | ✅ |
| `AccCopyPropagationPass` | ACC 拷贝传播 | ✅ |
| `ExpressionPropagationPass` | 表达式传播 | ✅ |
| `RedundantLoadEliminationPass` | 重复字段读取消除 | ✅ |

### 函数调用合并

检测并合并连续的 ACC 赋值和 CallAcc 调用：

```javascript
// 优化前
_acc_ = AtkTsGlobal.print;
_acc_ = this._acc_(_acc_);

// 优化后
_acc_ = AtkTsGlobal.print(_acc_);
```

### 函数名还原

解码方舟编译器的内部编码：
- `#*#foo` → `function foo`
- `#~A=#A` → `constructor`
- `#~A>#loop` → `loop`
- `#~A<#foo` → `static foo`

## 已实现功能

### P1（代码已实现，待更充分的测试数据验证）
1. **参数名还原**
   - `MethodItem.argsStr()` 优先读取 `DebugInfo.params`
   - 无调试信息时回退到 `arg0`, `arg1`, ...
   - ⚠️ 当前 `/Users/vv/project/unitTest/out` 官方测试 ABC 的 `DbgInfo.params` 均为空，尚未在真实数据中验证还原效果
2. **copyrestargs 实现**
   - 新增 `IrOp.CopyRestArgs(startIdx)`
   - 支持 `copyrestargs`（`0xcf`）和 `wide.copyrestargs`（前缀 `0xfd` + `0x0b`）
   - 函数签名中对 rest 参数标注 `...name`
   - ⚠️ 当前测试集（含 Kazumi HAP）中未找到使用 `copyrestargs` 指令的方法，尚未在真实数据中验证
3. **try-catch 边界标注**
   - `CodeSegment.genGraph()` 将 `tryBlock.startPc` / `startPc+length` / `catchBlock.handlerPc` / `handlerPc+codeSize` 作为 CFG 节点边界切分
   - `RegionGraphBuilder` 把 catch handler 从主控制流图剥离，避免干扰 if/while 结构化分析
   - `StructuredToJs` 在函数体顶部输出 try-catch 摘要，在每个线性基本块前后输出 `// try [...)` / `// end try [...)` 边界注释，并在函数末尾输出 `// catch handler ...` 注释块
   - 结构化分析失败时，降级为按基本块顺序输出，并保留 try/catch 标注
   - 超大方法截断输出时，`MethodSummary` 包含 `tryCatchSummary` 字段，展示 try 范围与 catch handler 范围
   - 已在 `Melotopia-1.10.3_HiCar_unsigned.hap` 上验证：1274 个含 try-catch 的方法全部生成 try/catch 标注
4. **async/await 反编译支持**
   - `IrOp` 新增 `AsyncFunctionEnter`、`Await` 等 IR 节点
   - 映射 `asyncfunctionenter` / `asyncfunctionawaituncaught` / `asyncfunctionresolve` / `asyncfunctionreject` / `suspendgenerator` / `resumegenerator` / `getresumemode` / `getasynciterator` 等指令
   - 函数头自动输出 `async function ...`
   - `await` 表达式渲染为 `_acc_ = await _acc_;`
   - `getresumemode` 固定翻译为 `0`，让正常 resume 分支生效；`throw` 作为 CFG 终止点，避免状态机展开导致的重复代码
   - 已在 `Melotopia-1.10.3_HiCar_unsigned.hap` 上验证：918 个 async 方法全部成功生成 `async function`，625 个含 await 指令的方法全部输出 `await` 关键字
   - ⚠️ 当前实现乐观地将 `getresumemode` 翻译为常量 `0`，因此正常 resume 分支会保留，异常/完成 resume 分支虽被 `throw` 终止点剪枝，但仍可能在输出中留下形式上可达的死分支（例如 `_acc_ = false; if (!(_acc_ == 0)) { throw ... }`）。后续可添加常量分支剪枝 pass 进一步清理。

## 待完成任务

### 优先级 P1
1. **验证参数名还原效果** 🔄
   - 已实现 `HapP1ValidationTest` 对 `/Users/vv/project/unitTest/hap` 下全部 HAP 进行扫描
   - 扫描 33239 个方法，当前样本中 `DebugInfo.params` 均为空（发行版 HAP 通常已剥离调试参数名）
   - 仍需找到携带 `DebugInfo.params` 的 ABC/HAP，确认 `arg0` → 真实参数名的还原正确
2. **验证 copyrestargs 效果** ✅
   - 在 `/Users/vv/project/unitTest/hap` 的 HMTG1.0.4.hap 等样本中找到 65 个包含 `copyrestargs` 的方法
   - 验证反编译签名均正确显示 `...argN`（例如 `LogUtil` 系列、`CharacterSetECI` 构造器）

### 优先级 P2
3. **增强常量折叠** ✅ - 在 `AlgebraicSimplificationPass` 中实现真正的常量-常量求值：二元数值运算、位运算、比较、`IsTrue`/`IsFalse`、`TypeOf`、`ToNumber`/`ToNumeric`、字符串拼接、`BigInt`；修复 `isZero`/`isOne` 对整数常量的识别；新增 `AlgebraicSimplificationPassTest`
4. **重复 Load 消除** ✅ - `RedundantLoadEliminationPass` 消除冗余的对象字段读取（`ObjField.Name`/`ObjField.Index`），支持写入失效、调用副作用失效、`DeleteProp`/`DefineGetterSetter` 失效；新增 `RedundantLoadEliminationPassTest`（14 个测试用例）
5. **交叉引用分析** - 查找方法/字段/类的调用者与实例化位置
   - ✅ 方法调用 xref 已实现：`get_xrefs_to_method` 工具 + `XRefIndex` 索引 + `SessionManager` 缓存
   - ✅ 字段读写 xref 已实现：`get_xrefs_to_field` 工具，支持 `this.fieldName` 精确索引与同名字段兜底索引
   - ✅ 类实例化 xref 已实现：`get_xrefs_to_class` 工具，索引 `NewClass` 与 `NewInst`（后者采用方法级寄存器回溯启发式）
   - ✅ 类 `instanceof` 检查 xref 已实现：在 `XRefIndex` 中索引 `IrOp.BiExp.InstOf`，并在 `get_xrefs_to_class` 结果中展示
   - ✅ 类层次结构查询已实现：`ClassHierarchyIndex` + `get_class_hierarchy` 工具，支持父类、接口、子类、接口实现者；对 ArkTS 编译的类还从 `NewClass` 指令的 parent 寄存器回溯 extends 关系，并将导入别名规范化为全限定类名
   - 待扩展：类型标注引用（参数/变量类型标注）
6. **优化反编译输出中的方法名显示** ✅
   - 已去掉 `TAG_NORMAL` 返回的 `function ` 前缀，解决 `function function [ANONYMOUS]` 问题
   - 已在 `decodeMethodName` 中剥离 `^N` 变体编号后缀
   - 已合并 `StructuredToJs` 与 `ToJs` 中重复的 `decodeMethodName` 实现
   - 输出统一为 `function foo(...)` / `function [ANONYMOUS](...)` / `function constructor(...)` / `function static foo(...)`
7. **资源索引解析器 `ResIndexBuf` 兼容性** ✅
   - 已支持旧版 `Restool 4.x` 与新版 `RestoolV2 6.1.0.003` 两种 `resources.index` 格式
   - 通过检测 `KEYS` 标签位置自动选择解析分支：旧格式在偏移 136 处、新格式在偏移 140 处
   - `search_resources` / `resolve_resource` / `open_hap` 在 Kazumi HAP 上已可正常工作
8. **方法内搜索** ✅
   - 新增 `search_in_method` 工具：在指定方法内按正则搜索反汇编文本，返回匹配行及上下文
   - 不触发完整反编译，因此适用于超大方法，不受 100 行展示上限和 10 MB 输出预算限制
   - 可同时定位字符串常量、方法调用、字段访问等在方法体内的具体位置
9. **模块变量指令实现** ✅
   - 实现 `stmodulevar`（0x7c）/ `wide.stmodulevar`（0xfd+0x0f）：将 ACC 存储到模块 localExports 槽位
   - 实现 `ldlocalmodulevar`（0x7d）/ `wide.ldlocalmodulevar`（0xfd+0x10）：从模块 localExports 槽位加载到 ACC
   - 新增 IR 操作 `AssignModuleVar`（Statement）和 `LoadLocalModuleVar`（NoRegExpression）
   - 代码生成：`AssignModuleVar` 渲染为 `name = value;`，`LoadLocalModuleVar` 渲染为变量名
   - 反编译输出中自动生成 export 声明
10. **call 系列 range 指令实现** ✅
    - 实现 `callthisrange`（0x31）：`this.method(arg0, arg1, ...)` 形式的方法调用
    - 实现 `supercallthisrange`（0x32）：`super(arg0, arg1, ...)` 形式的父类构造函数调用
    - 实现 `callrange`（0x73）：`func(arg0, arg1, ...)` 形式的函数调用（无 this 绑定）
    - 实现 `supercallarrowrange`（0xbb）：arrow 函数的 super 调用
    - 实现 wide 变体（0xfd+0x04/05/06/07）
    - 复用现有 `CallAcc` IR 操作，优化器和代码生成零改动
    - HISH 中消除 155 处未实现指令（从 2894 降至 2739）
11. **definemethod 指令实现** ✅
    - 实现 `definemethod`（0x34）/ `definemethod`（0xbe，16-bit imm1）：ACC 透传（`acc=inout:top`），不阻断反编译流程
    - HISH 中消除 475 处未实现指令（从 2739 降至 2264）
12. **`open_hap` 自动提取 ABC** ✅
    - `open_hap` 解析时自动将 ABC 文件提取到 HAP 同目录下的 `{name}_abc/` 文件夹
    - 返回可直接传给 `open_abc` 的文件系统路径，LLM 无需手动解压
13. **`get_class_detail` 展示 import/export 元数据** ✅
    - 新增 Imports 和 Export 显示，从 `ModuleLiteralArray` 读取
14. **反编译输出优化** ✅
    - 过滤 `/* nop */` 和 `/* acc: xxx */` 注释，减少无意义输出
    - `ExpressionPropagationPass.resolveReg` 新增循环检测，防止寄存器引用链无限递归
15. **`throw.ifsupernotcorrectcall` 指令实现** ✅
    - 实现 `throw.ifsupernotcorrectcall`（prefix `0xfe`，opcode `0x07` imm8 / `0x08` imm16）
    - 该指令为方舟编译器在 `super()` / `super.method()` 调用前插入的运行时校验，正常代码不触发，无对应 TS/ArkTS 语法
    - 映射为 `IrOp.NOP`，消除反编译输出中 `/* unimplemented: throw.ifsupernotcorrectcall */` 占位注释
    - 在 `/home/orz/project/unitTest/hap` 样本中验证：455 个含该指令的方法均不再输出未实现注释
16. **词法环境指令实现与变量名解析** ✅
    - 实现 `newlexenv`（0x09）/ `wide.newlexenv`（0xfd 0x02）、`newlexenvwithname`（0xb6）/ `wide.newlexenvwithname`（0xfd 0x03）、`poplexenv`（0x69）/ `deprecated.poplexenv`（0xfc 0x01）
    - 新增 IR 节点 `NewLexEnv` / `NewLexEnvWithName` / `PopLexEnv`（均为 NOP，不污染输出）
    - 解析 `newlexenvwithname` 引用的 literal array，建立词法环境栈快照
    - 在 `StructuredToJs` 与 `ToJs` 中按原始指令位置维护词法环境栈，将 `ldlexvar`/`stlexvar` 的 `__lex{lvl}_{slot}__` 占位符替换为真实变量名
    - 对未知槽位或 `newlexenv`（无 name）回退 `__lex{lvl}_{slot}__`，避免输出混淆
    - 新增 `LexEnvNameResolver` 工具类、`LexEnvNameResolutionTest` 单元测试、`LexEnvValidationTest` HAP 扫描验证
    - 在 `/home/orz/project/unitTest/hap` 样本中验证：1562 个含词法环境指令的方法全部正确映射 IR，1180 个含 `newlexenvwithname` 的方法中 974 个成功将 `__lexXX__` 解析为真实名称
17. **`starrayspread` 数组展开指令实现** ✅
    - 新增 IR 模型：`ArrayElement` / `ArrayLiteral` / `SpreadIntoArray`
    - 将 `createemptyarray` / `createarraywithbuffer` 映射为 `ArrayLiteral`，`starrayspread` 映射为以 ACC 为源对象的 `SpreadIntoArray`
    - 实现 `ArraySpreadMergingPass`：将连续的 `SpreadIntoArray` 合并到可追踪的 `ArrayLiteral` 赋值中，生成 `[elem1, ...src, elemN]` 形式
    - 无法追踪或源含副作用时降级为 `arr.push(...src);`，保证语义正确
    - `StructuredToJs` / `ToJs` 新增数组字面量与展开元素渲染
    - 新增 `ArraySpreadMergingPassTest` 单元测试、`StarrayspreadValidationTest` HAP 扫描验证
    - 在 `/home/orz/project/unitTest/hap` 样本中验证：58 个含 `starrayspread` 的方法全部成功反编译，其中 34 个生成 `[...x]` 字面量形式（24 个因数组对象无法在同一基本块追踪而降级为 `.push(...)`）
18. **迭代器相关指令实现与 for-of/for-in 语法降级** ✅
    - 实现 `getiterator`（0x67/0xab）、`getpropiterator`（0x66）、`getnextpropname`（0x36）、`closeiterator`（0x68/0xac）、`deprecated.getiteratornext`（0xfc 0x02）的 IR 映射
    - 新增 `ForOfStatement` / `ForInStatement` 语句类型
    - 在 `StructuredToJs` 中预扫描 `iterator init + while` 模式，将其降级为 `for (const x of iterable)` / `for (const k in obj)`；无法识别时回退到低层 `while` 并保留 `Symbol.iterator()` / `Object.keys()` 表达式
    - 修复 `CodeSegment.genGraph` 对 `JumpIf` 前缀指令的拆分，避免迭代器循环头部的副作用语句被丢弃
    - 修复 `RegionGraphBuilder.cleanUnreachableNodes` 迭代清理不可达节点，防止 for-of 中 cleanup 块导致结构化分析失败
    - 新增 `IteratorOpcodeTest` 单元测试（es2abc 编译真实 JS 片段后反编译验证）
    - 新增 `IteratorOpcodeValidationTest` HAP 扫描验证：对 `/home/orz/project/unitTest/hap` 抽样含迭代器 opcode 的方法反编译，确保不崩溃

### 已修复
- ✅ HAP `module.json` / `obfuscation.map` JSON 解析兼容性：添加 `ignoreUnknownKeys = true`，支持 Kazumi HAP 中的额外字段（如 `iconId`）
- ✅ `open_hap` 资源解析异常处理：捕获 `Throwable`，避免不兼容 `resources.index` 导致 OOM 崩溃
- ✅ `HapSignBlocks` 整数溢出修复：大 HAP 文件（>2GB 偏移）的签名块解析 `.toInt()` 溢出导致 `Range out of bounds` 错误，新增 Long 范围检查

### 未实现指令（剩余主要类别，基于实现前快照）

> async/await 与 generator 相关指令已实现，下表不再包含该类别。

| 类别 | 指令 | 命中 | 说明 |
|------|------|------|------|
| 其他 | `callruntime.*` | - | 并发任务相关运行时调用 |

> 当前 `/home/orz/project/unitTest/hap/hish.20250704.hap` 扫描结果（HISH）：`getiterator` 与 `throw.ifnotobject` 已实现，剩余未实现主要集中在 `callruntime.*` 系列（如 `callruntime.notifyconcurrentresult`、`callruntime.supercallforwardallargs` 等）。

### 未来工作

#### 词法环境指令输出优化

当前词法环境名称解析已能正确工作，但在含 `newlexenv*` / `poplexenv` 的基本块中禁用了完整 IR 优化，导致输出保留 `_acc_ = arg0; menuItems = _acc_;` 这类 ACC 中转语法。下一步优化方向：

1. **恢复词法环境块内的 ACC 拷贝传播**
   - 目标：将 `_acc_ = X; Y = _acc_;` 合并为 `Y = X;`
   - 依赖：修复/增强优化器对 lexical 变量（`__lexXX__`）和闭包作用域的处理
2. **让词法环境节点在优化过程中可追踪**
   - 当前 `NewLexEnv` / `NewLexEnvWithName` / `PopLexEnv` 为 `TraitNOP`，优化器直接透传
   - 可改为非 NOP 标记节点，使优化器在传播表达式时 aware 到作用域边界，避免跨环境错误传播
3. **消除 `.length` 链式膨胀等错误表达式**
   - 在 `ExpressionPropagationPass` / `CopyPropagationPass` 中增加对 lexical 寄存器和循环引用的检测
   - 参考 `ExpressionPropagationPass.resolveReg` 已有的循环检测机制，统一加到所有传播路径
4. **补充回归测试**
   - 在 `LexEnvValidationTest` 中增加断言：反编译输出不能出现 `.length` 无限重复等异常模式
   - 选取 3~5 个典型闭包方法作为固定样本，检查输出语法正确性

#### class 语法重组

`func_main_0` 中的 `defineclasswithbuffer` + `definemethod` + `Object.defineProperty` 模式可重组为 `class Foo extends Bar { method() {...} }` 语法。当前 `definemethod` 作为 NOP 透传，不影响反编译流程但不生成 class 语法。

调研结论：
- `func_main_0` 是 ArkCompiler 生成的**模块入口函数**，对应 `.ets` 源文件的顶层作用域。
- `get_class_detail` 目前返回的是**文件级 module record**，真正的 ArkTS 类藏在 `func_main_0` 的 `defineclasswithbuffer` 指令里。
- class 重组应只提取 `func_main_0` 中的 class 创建块，其余 import、顶层变量、常量初始化等代码保持原样。

详细方案见 [`docs/class-reconstruction-research.md`](docs/class-reconstruction-research.md)。

## 修改约定

- 若修改了 AGENTS.md 中提到的模块结构、构建配置、工具列表、JDK 支持情况等内容，需同步更新本文件。
- 保持对原项目 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 的致谢和链接。
