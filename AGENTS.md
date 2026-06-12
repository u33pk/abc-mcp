<!-- From: /Users/vv/project/abc-mcp/AGENTS.md -->
# AGENTS.md - ABCDE MCP 项目指南

## 项目背景

本项目 **ABCDE MCP** 是基于 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 的 OpenHarmony 逆向工具链。

- **原项目作者**：Yricky
- **原仓库地址**：[https://github.com/Yricky/ABCDE](https://github.com/Yricky/ABCDE)
- **本项目定位**：在 ABCDE 基础上封装 **MCP（Model Context Protocol）服务**，为 LLM 提供鸿蒙应用的反编译与分析能力。

## 技术栈

- **语言**：Kotlin Multiplatform（核心为 JVM）
- **GUI**：Jetpack Compose Desktop（非核心）
- **构建**：Gradle (Kotlin DSL)
- **Kotlin 版本**：2.0.21
- **Gradle 版本**：8.6

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
| **JDK 17** | ✅ 推荐 | 项目原生 `jvmToolchain(17)` 配置 |
| **JDK 21** | ✅ 可用 | 需将所有模块的 `jvmToolchain(17)` 改为 `jvmToolchain(21)` |
| **JDK 25** | ❌ 不支持 | Gradle 8.6 内置 Kotlin 无法识别 Java 25 |
| **JDK 26** | ❌ 不支持 | Gradle 8.6 内置 Kotlin 无法识别 Java 26 |

> 当前 Kotlin 2.0.21 最高支持到 **JVM target 22**。若需支持 JDK 25/26，必须升级 Gradle 至 8.12+、Kotlin 至 2.1.x+。

## 常用构建命令

```bash
# 启动 MCP 服务
./gradlew :modules:mcp:jvmRun

# 构建 MCP 可运行 fat jar（包含所有依赖）
./gradlew :modules:mcp:fatJar

# 构建桌面 GUI 的 UberJar
./gradlew :abcdecoder:packageReleaseUberJarForCurrentOS

# 发布到本地 Maven
./gradlew publishToMavenLocal
```

## 测试

```bash
# 运行所有反编译测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testAllAbcFiles"

# 运行单个文件测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testSpecificFile"
```

测试文件路径（参考）：
- 源码：`/home/orz/project/unitTest/`
- ABC 文件：`/home/orz/project/unitTest/out/`

## MCP 服务

### 模块位置
`modules/mcp/`

### 入口
`me.yricky.oh.mcp.MainKt`

### 暴露的 Tools（13 个）

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

### LLVM 风格优化 Pass

| Pass | 说明 | 状态 |
|------|------|------|
| `AlgebraicSimplificationPass` | 代数化简（常量折叠、恒等变换） | ✅ |
| `CopyPropagationPass` | 拷贝传播 | ✅ |
| `DeadCodeEliminationPass` | 死代码消除 | ✅ |
| `AccCopyPropagationPass` | ACC 拷贝传播 | ✅ |
| `ExpressionPropagationPass` | 表达式传播 | ✅ |

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

## 待完成任务

### 优先级 P1
1. **参数名还原** - 从调试信息提取 `arg0` → `i`，`arg1` → `j`
2. **copyrestargs 实现** - 剩余参数收集（目前是 UnImplemented）

### 优先级 P2
3. **增强常量折叠** - 嵌套常量、布尔简化
4. **重复 Load 消除** - 消除冗余的对象字段读取
5. **交叉引用分析** - 查找方法/字段的调用者

## 修改约定

- 若修改了 AGENTS.md 中提到的模块结构、构建配置、工具列表、JDK 支持情况等内容，需同步更新本文件。
- 保持对原项目 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 的致谢和链接。
