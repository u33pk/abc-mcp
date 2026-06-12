# AGENTS.md - ABCDE 项目指南

## 项目概述

ABCDE 是一个 OpenHarmony 逆向工具包，用于解析和反编译方舟字节码文件（`.abc`）。

### 核心目标
构建一个 **MCP 服务**，供 LLM 调用，实现鸿蒙应用的反编译和分析能力。

### 技术栈
- **语言**: Kotlin Multiplatform
- **GUI**: Jetpack Compose Desktop（非核心）
- **构建**: Gradle (Kotlin DSL)

---

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
    │   ├── IrOp.kt              # IR 节点定义
    │   ├── FunSimCtx.kt         # 寄存器定义（ACC, GLOBAL, THIS 等）
    │   └── suger.kt             # 扩展函数（replaceReg 等）
    ├── CodeSegment.kt           # CFG 基本块定义
    └── structure/               # 新结构化分析
        ├── RegionGraph.kt       # 轻量级有向图
        ├── Region.kt            # 区域节点定义
        ├── DominatorGraph.kt    # 支配树
        ├── LoopFinder.kt        # 循环检测
        ├── RegionGraphBuilder.kt # CFG → RegionGraph
        ├── StructureAnalysis.kt # 结构化分析核心
        ├── StructuredDecompiler.kt # 统一入口
        ├── StructuredToJs.kt    # 代码生成器
        ├── IrOpOptimizer.kt     # 优化器（Pass 架构）
        ├── OptimizationPasses.kt # 优化 Pass 实现
        └── statement/           # 语句类型
```

---

## 已实现功能

### 1. 结构化分析（从 xpanda 移植）

xpanda 是另一个鸿蒙反编译器（Java 实现，已停止维护）。我们将其核心算法移植到了 ABCDE。

#### 移植的组件
1. **DominatorGraph** - 支配树计算（Cooper-Harvey-Kennedy 算法）
2. **LoopFinder** - 基于支配关系的循环检测
3. **StructureAnalysis** - 控制流规约算法（if/while/do-while/逻辑短路）
4. **RegionGraph** - 轻量级有向图（替代 Guava MutableValueGraph）

### 2. LLVM 风格优化

| Pass | 说明 | 状态 |
|------|------|------|
| `AlgebraicSimplificationPass` | 代数化简（常量折叠、恒等变换） | ✅ |
| `CopyPropagationPass` | 拷贝传播 | ✅ |
| `DeadCodeEliminationPass` | 死代码消除 | ✅ |
| `AccCopyPropagationPass` | ACC 拷贝传播 | ✅ |
| `ExpressionPropagationPass` | 表达式传播 | ✅ |

### 3. 函数调用合并

检测并合并连续的 ACC 赋值和 CallAcc 调用：
```javascript
// 优化前
_acc_ = AtkTsGlobal.print;
_acc_ = this._acc_(_acc_);

// 优化后
_acc_ = AtkTsGlobal.print(_acc_);
```

支持嵌套属性访问：
```javascript
// console.log(i*j) 合并为
_acc_ = AtkTsGlobal.console.log(_acc_);
```

### 4. 函数名还原

解码方舟编译器的内部编码：
- `#*#foo` → `function foo`
- `#~A=#A` → `constructor`
- `#~A>#loop` → `loop`
- `#~A<#foo` → `static foo`

### 5. MCP 服务

提供 13 个工具供 LLM 调用，支持 ABC 解析、HAP 包分析、资源搜索等。

---

## 测试

### 测试文件
- 源码：`/home/orz/project/unitTest/`
- ABC 文件：`/home/orz/project/unitTest/out/`

### 运行测试
```bash
# 运行所有测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testAllAbcFiles"

# 运行单个文件测试
./gradlew :modules:abcde:jvmTest --tests "me.yricky.oh.abcd.decompiler.structure.StructuredDecompilerTest.testSpecificFile"
```

### 测试结果
- 总方法数：204
- 成功：204（100%）
- 失败：0

---

## MCP 服务

### 模块位置
`modules/mcp/`

### 启动方式
```bash
./gradlew :modules:mcp:jvmRun
```

### 暴露的 Tools

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

---

## 待完成任务

### 优先级 P1
1. **参数名还原** - 从调试信息提取 `arg0` → `i`，`arg1` → `j`
2. **copyrestargs 实现** - 剩余参数收集（目前是 UnImplemented）

### 优先级 P2
3. **增强常量折叠** - 嵌套常量、布尔简化
4. **重复 Load 消除** - 消除冗余的对象字段读取
5. **交叉引用分析** - 查找方法/字段的调用者

---

## 从 xpanda 移植的组件

xpanda 是另一个鸿蒙反编译器（Java 实现，已停止维护）。我们将其核心算法移植到了 ABCDE。

### 移植的组件
1. **DominatorGraph** - 支配树计算（Cooper-Harvey-Kennedy 算法）
2. **LoopFinder** - 基于支配关系的循环检测
3. **StructureAnalysis** - 控制流规约算法（if/while/do-while/逻辑短路）
4. **RegionGraph** - 轻量级有向图（替代 Guava MutableValueGraph）

### 未移植的组件
- xpanda 的文件解析层（ABCDE 已有 AbcBuf）
- xpanda 的指令定义（ABCDE 使用官方 isa.yaml）
- xpanda 的 GraalJS 代码生成（我们用 Kotlin 字符串拼接）
- xpanda 的 Node.js 代码简化（我们不需要）
