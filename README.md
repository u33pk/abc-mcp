# ABCDE MCP

> 基于 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 的 OpenHarmony 逆向分析工具链
>
> 将 ABCDE 的反编译能力封装为 **MCP（Model Context Protocol）服务**，让 Claude、Cursor 等 LLM 客户端能够直接分析鸿蒙应用（`.abc` / `.hap`）。

## 项目特色

- **LLM 原生设计**：所有能力以 MCP Tool 形式暴露，输入输出都对 LLM 友好。
- **结构化反编译**：移植自 xpanda 的控制流分析算法，输出可读性更强的 `if` / `while` / `do-while` 结构，而非线性指令列表。
- **超大方法安全**：对输出爆炸的方法实施多层防护（10 MB 服务端硬预算、100 行 LLM 展示上限、尾节点复制限制），截断时仍返回方法签名、前段代码和方法摘要。
- **交叉引用（XRef）**：支持查找方法的调用者，帮助 LLM 快速追踪调用链。
- **HAP 全栈解析**：同时支持 ABC 字节码、HAP 包结构、资源索引和混淆映射。

## 核心功能

| 能力 | 说明 |
|------|------|
| **ABC 字节码解析** | 解析方舟字节码中的类、方法、字段、字面量数组、调试信息等 |
| **结构化反编译** | 将字节码反编译为 JavaScript/TypeScript 风格代码 |
| **方法交叉引用** | 查找指定方法在哪些类/方法中被调用 |
| **字符串搜索** | 按正则搜索 ABC 中的字符串常量 |
| **HAP 包解析** | 解析鸿蒙应用安装包，提取 module.json、ABC 文件、资源、混淆映射等 |
| **资源索引解析** | 解析 `resources.index`，支持按名称/类型搜索资源 |
| **MCP 服务** | 通过 stdio JSON-RPC 为 LLM 提供 16 个标准化工具 |

## 技术栈

- **语言**：Kotlin Multiplatform（核心为 JVM）
- **构建**：Gradle Kotlin DSL
- **GUI**：Jetpack Compose Desktop（桌面查看器，非核心）
- **依赖**：kotlinx-serialization-json、BouncyCastle

## JDK 支持情况

| JDK 版本 | 支持状态 | 说明 |
|---------|---------|------|
| **JDK 17 / 21** | ✅ 可运行 Gradle | 编译时仍需 JDK 26 toolchain，建议通过 `org.gradle.java.installations.paths` 配置 |
| **JDK 26** | ✅ 推荐 | 当前默认 `jvmToolchain(26)`，与项目编译目标一致 |
| **JDK 27+** | ❌ 不支持 | Gradle 9.5 / Kotlin 2.4.0 尚未支持 |

> 当前配置：Gradle 9.5.1 + Kotlin 2.4.0 + Compose Multiplatform 1.11.1。

## 环境要求

- JDK 17+ 可运行 Gradle；编译需要 JDK 26（推荐与本项目 `jvmToolchain(26)` 一致）
- 网络连接（首次构建需下载依赖）

## 快速开始

### 1. 启动 MCP 服务

```bash
./gradlew :modules:mcp:jvmRun
```

### 2. 构建可独立运行的 fat jar

```bash
./gradlew :modules:mcp:fatJar
java -jar modules/mcp/build/libs/mcp-*-fat.jar
```

fat jar 会包含 `abcde`、`resde`、`hapde`、kotlinx-serialization、coroutines 等所有依赖。

### 3. 构建桌面 GUI

```bash
./gradlew :abcdecoder:packageReleaseUberJarForCurrentOS
```

## MCP 服务配置

### Cursor 配置示例

在 `.cursor/mcp.json` 中添加：

```json
{
  "mcpServers": {
    "abcde": {
      "command": "/path/to/gradlew",
      "args": [":modules:mcp:jvmRun"],
      "cwd": "/path/to/abcde-mcp"
    }
  }
}
```

或使用 fat jar：

```json
{
  "mcpServers": {
    "abcde": {
      "command": "java",
      "args": ["-jar", "/path/to/abcde-mcp/modules/mcp/build/libs/mcp-0.1.0-main-fat.jar"]
    }
  }
}
```

## 可用工具（16 个）

### ABC 字节码工具

| 工具 | 功能 |
|------|------|
| `open_abc` | 打开 ABC 文件，返回版本、类数量等基本信息 |
| `list_classes` | 列出所有类名，支持正则过滤 |
| `get_class_detail` | 获取类详情（父类、字段、方法列表） |
| `decompile_class` | 反编译指定类的所有方法 |
| `decompile_method` | 反编译指定方法 |
| `search_strings` | 搜索字符串常量（正则匹配） |
| `disassemble_method` | 获取方法的字节码反汇编（含参数与注释） |
| `get_method_info` | 获取方法详情（参数名、行号、调试信息） |
| `get_xrefs_to_method` | 查找方法的调用者（交叉引用） |
| `get_xrefs_to_field` | 查找字段的读取者和写入者（交叉引用） |
| `search_in_method` | 在方法体内按正则搜索反汇编文本，返回匹配行及上下文 |

### HAP 包工具

| 工具 | 功能 |
|------|------|
| `open_hap` | 解析 HAP 包，列出 ABC 文件和资源统计 |
| `get_hap_manifest` | 获取 module.json 清单内容 |
| `get_obfuscation_map` | 获取混淆映射（obfuscated → original） |

### 资源工具

| 工具 | 功能 |
|------|------|
| `search_resources` | 搜索资源（按名称、类型） |
| `resolve_resource` | 解析 `$string:app_name` 等资源引用 |

## 命令行工具

```bash
# 导出 ABC 中的类列表
java -jar /path/to/abcdecoder.jar --cli --dump-class /path/to/module.abc [--out=out.txt]

# 导出资源索引
java -jar /path/to/abcdecoder.jar --cli --dump-index /path/to/resources.index [--out=out.json]
```

## 测试

```bash
# 运行所有反编译测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testAllAbcFiles"

# 运行单个文件测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testSpecificFile"

# 运行交叉引用测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.xref.*"

# 运行 MCP 工具测试
./gradlew :modules:mcp:jvmTest
```

## 项目结构

```
modules/
├── common/          # 通用基础类型（LEByteBuf 等）
├── abcde/           # 核心 ABC 字节码解析、反汇编、反编译库
├── resde/           # 资源索引解析（.index 文件）
├── hapde/           # HAP 包解析（.hap 文件）
└── mcp/             # MCP 服务模块
```

## 相关文档

- [将 abcde 引入其他项目](docs/libabcde.md)
- [包体积分析工具示例](examples/abclen)
- [字符串查找工具示例](examples/findStr)

## 参考项目

| 项目 | 说明 |
|------|------|
| [Yricky/ABCDE](https://github.com/Yricky/ABCDE) | 基础 ABC 解析与反编译项目，本项目在其上封装 MCP 服务 |
| [asmjmp0/xpanda](https://github.com/asmjmp0/xpanda) | 已停止维护的鸿蒙反编译器，本项目的结构化控制流分析算法移植自此处 |
| [skylot/jadx](https://github.com/skylot/jadx) | Android 反编译器，本项目的交叉引用索引与缓存设计借鉴了其 UsageInfo / IUsageInfoCache 架构 |

## 基础项目、作者与开源协议

本项目基于 **[Yricky/ABCDE](https://github.com/Yricky/ABCDE)** 构建：

- **原作者**：Yricky
- **原项目地址**：[https://github.com/Yricky/ABCDE](https://github.com/Yricky/ABCDE)
- **开源协议**：Apache License 2.0

感谢原作者在 OpenHarmony 逆向领域的开源贡献。
