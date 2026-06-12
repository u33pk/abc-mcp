# ABCDE
> OpenHarmony 逆向工具包 by Yricky
> 
> 项目中的其他文档：
> - [将abcde(abc文件解析库)引入项目](docs/libabcde.md)
> - [包体积分析工具示例](examples/abclen)
> - [字符串查找工具示例](examples/findStr)

## 项目概述

ABCDE 是一个使用 Kotlin 编写的 OpenHarmony 逆向工具包，提供以下核心功能：

- **ABC 字节码解析** - 解析方舟字节码文件中的类、方法、字段、字面量数组等信息
- **反编译** - 将方舟字节码反编译为结构化的 JavaScript 代码
- **HAP 包解析** - 解析鸿蒙应用安装包，提取配置、签名、资源等信息
- **资源索引解析** - 解析 resources.index 文件，支持多语言、多设备类型等限定符
- **MCP 服务** - 为 LLM 提供标准化的工具接口，支持 AI 辅助逆向分析

该工具核心功能由纯 Kotlin（JVM）实现，可提供平台无关的 JAR 包供 Java 工程引用并二次开发。

## 构建

### 环境需求
- JDK 17+

### 构建 UberJar
```shell
./gradlew :abcdecoder:packageReleaseUberJarForCurrentOS
```

### 启动 MCP 服务
```shell
./gradlew :modules:mcp:jvmRun
```

> 也可以去 [GitHub Actions](https://github.com/Yricky/abcde/actions) 中下载最新的构建

## 功能演示

### 主页面
![main](docs/image/2025-01-02%2000.44.07.webp)
可以拖入或点击打开文件，支持文件类型有 .abc、.hap、.index 等

### HAP 页面
![hap](docs/image/2025-01-02%2000.45.38.webp)
这里可以以树形结构查看 HAP 中的内容，其中的 ABC 和 INDEX 文件能够点击打开。
如果解析成功，该 HAP 的部分包信息将在右侧展示。

### 资源索引查看页面
![resi](docs/image/2025-01-02%2000.46.57.webp)
![resi2](docs/image/2025-01-02%2000.47.01.webp)

这里提供与 Android Studio 查看 ARSC 类似的功能，可以查看 OpenHarmony 资源索引文件中的内容，并支持按类型区分和名称+内容的查找。

### ABC 文件查看页面
![abc](docs/image/2025-01-02%2000.48.52.webp)
这里可以按照树形结构查看 ABC 字节码文件中的类信息，支持按名称查找。左侧信息页签也支持查看字节码版本、校验和等信息。

### 类信息和字节码查看
![class](docs/image/2025-01-02%2000.50.08.webp)
点入某个类后，可以查看类的方法和字段。支持简单的索引，左侧信息页签中可以查看这个类的导入导出信息

![bytecode](docs/image/2025-01-02%2000.50.37.webp)
点击类中的某个方法即可查看该方法的字节码。

> 这里展示的字节码相比于官方工具，额外提供了如下实用功能：
> 1. 对于引用了导入模块的指令，展示可读的模块导入
> 2. 如果是 HAP 包中的 ABC 文件，会同时尝试解析 HAP 包中的资源索引并在引用处展示为字符串
> 3. Ctrl+单击指令引用的方法可以跳转

### 反编译

ABCDE 支持将方舟字节码反编译为结构化的 JavaScript 代码，采用基于控制流规约的结构化分析算法：

- 支持 if/else-if/else 条件分支
- 支持 for/while/do-while 循环
- 支持 break/continue 控制流
- 支持嵌套循环和复杂条件
- 支持函数调用合并优化
- 支持函数名还原（`#*#foo` → `function foo`）
- 测试通过率：204/204 (100%)

![discompiler_demo](docs/image/2025-03-09%2021.58.31.webp)

## MCP 服务

ABCDE 提供 MCP（Model Context Protocol）服务，可与 Claude、Cursor 等 LLM 客户端集成，实现 AI 辅助逆向分析。

### 配置示例（Cursor）

在 `.cursor/mcp.json` 中添加：

```json
{
  "mcpServers": {
    "abcde": {
      "command": "/path/to/gradlew",
      "args": [":modules:mcp:jvmRun"],
      "cwd": "/path/to/abcde"
    }
  }
}
```

### 可用工具（13 个）

#### ABC 字节码工具
| 工具 | 功能 |
|------|------|
| `open_abc` | 打开 ABC 文件，返回版本、类数量等信息 |
| `list_classes` | 列出所有类名，支持正则过滤 |
| `get_class_detail` | 获取类的父类、字段、方法列表 |
| `decompile_class` | 反编译指定类的所有方法 |
| `decompile_method` | 反编译指定方法 |
| `search_strings` | 搜索字符串常量（正则匹配） |
| `disassemble_method` | 获取方法的字节码反汇编 |
| `get_method_info` | 获取方法的参数名、行号、调试信息 |

#### HAP 包工具
| 工具 | 功能 |
|------|------|
| `open_hap` | 解析 HAP 包，列出 ABC 文件和资源统计 |
| `get_hap_manifest` | 获取 module.json 清单内容 |
| `get_obfuscation_map` | 获取混淆映射（obfuscated → original） |

#### 资源工具
| 工具 | 功能 |
|------|------|
| `search_resources` | 搜索资源（按名称、类型） |
| `resolve_resource` | 解析 `$string:app_name` 等资源引用 |

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
        ├── DominatorGraph.kt    # 支配树
        ├── LoopFinder.kt        # 循环检测
        ├── StructureAnalysis.kt # 结构化分析核心
        ├── StructuredDecompiler.kt # 统一入口
        ├── StructuredToJs.kt    # 代码生成器
        └── OptimizationPasses.kt # 优化 Pass
```

## 命令行

目前支持使用命令行解析出 ABC 文件中的 class 列表和资源索引文件中的内容：

> dump class
```shell
java -jar /path/to/abcdecoder.jar --cli --dump-class /path/to/module.abc [--out=out.txt]
```

> dump index
```shell
java -jar /path/to/abcdecoder.jar --cli --dump-index /path/to/resources.index [--out=out.json]
```

## 测试

### 运行测试
```shell
# 运行所有测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testAllAbcFiles"

# 运行单个文件测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testSpecificFile"
```

### 测试文件
- 源码：`/home/orz/project/unitTest/`
- ABC 文件：`/home/orz/project/unitTest/out/`

## 技术栈

- **语言**: Kotlin Multiplatform
- **GUI**: Jetpack Compose Desktop
- **构建**: Gradle (Kotlin DSL)
- **依赖**: kotlinx-serialization-json, BouncyCastle

## License

Apache License 2.0
