# ABCDE MCP

> 基于 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 的 OpenHarmony 逆向分析工具链
>
> 原项目作者：**Yricky** | 原仓库：[https://github.com/Yricky/ABCDE](https://github.com/Yricky/ABCDE)

本项目在 ABCDE 基础上聚焦 **MCP（Model Context Protocol）服务**，为 LLM 提供标准化的鸿蒙应用反编译与分析能力。

## 核心能力

- **ABC 字节码解析** - 解析方舟字节码文件中的类、方法、字段、字面量数组等
- **反编译** - 将方舟字节码反编译为结构化的 JavaScript 代码
- **HAP 包解析** - 解析鸿蒙应用安装包，提取配置、签名、资源等
- **资源索引解析** - 解析 `resources.index` 文件，支持多语言/设备限定符
- **MCP 服务** - 为 Claude、Cursor 等 LLM 客户端提供 13 个标准化工具

## 技术栈

- **语言**：Kotlin Multiplatform（核心为 JVM）
- **构建**：Gradle Kotlin DSL
- **GUI**：Jetpack Compose Desktop（桌面查看器，非核心）
- **依赖**：kotlinx-serialization-json、BouncyCastle

## JDK 支持情况

| JDK 版本 | 支持状态 | 说明 |
|---------|---------|------|
| **JDK 17** | ✅ 推荐 | 项目原生配置，最稳定 |
| **JDK 21** | ✅ 可用 | 需将 `jvmToolchain(17)` 改为 `jvmToolchain(21)` |
| **JDK 25** | ❌ 不支持 | 当前 Gradle 8.6 + Kotlin 2.0.21 无法识别/编译到 JDK 25 |
| **JDK 26** | ❌ 不支持 | 当前 Gradle 8.6 + Kotlin 2.0.21 无法识别/编译到 JDK 26 |

> 如需支持 JDK 25/26，需升级 Gradle 至 8.12+、Kotlin 至 2.1.x+。

## 环境要求

- JDK 17+（推荐 17，21 也可工作）
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

## 可用工具（13 个）

### ABC 字节码工具

| 工具 | 功能 |
|------|------|
| `open_abc` | 打开 ABC 文件，返回版本、类数量等基本信息 |
| `list_classes` | 列出所有类名，支持正则过滤 |
| `get_class_detail` | 获取类详情（父类、字段、方法列表） |
| `decompile_class` | 反编译指定类的所有方法 |
| `decompile_method` | 反编译指定方法 |
| `search_strings` | 搜索字符串常量（正则匹配） |
| `disassemble_method` | 获取方法的字节码反汇编 |
| `get_method_info` | 获取方法详情（参数名、行号、调试信息） |

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

## 致谢

本项目基于 [Yricky/ABCDE](https://github.com/Yricky/ABCDE) 构建，感谢原作者在 OpenHarmony 逆向领域的开源贡献。

## License

Apache License 2.0
