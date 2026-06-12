# ABCDE 项目 - 鸿蒙 ABC 字节码反编译工具

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
└── mcp/             # MCP 服务模块（新增）
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
    ├── Linearizer.kt            # 旧的模式匹配线性化器
    ├── ToJs.kt                  # 旧的代码生成器
    └── structure/               # 新结构化分析（从 xpanda 移植）
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
        ├── IrOpOptimizer.kt     # 优化器入口
        └── statement/           # 语句类型
```

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

---

## LLVM 风格优化

### 已实现的优化 Pass

| Pass | 说明 | 状态 |
|------|------|------|
| `AlgebraicSimplificationPass` | 代数化简（常量折叠、恒等变换） | ✅ |
| `CopyPropagationPass` | 拷贝传播 | ✅ |
| `DeadCodeEliminationPass` | 死代码消除 | ✅ |
| `AccCopyPropagationPass` | ACC 拷贝传播 | ✅ |
| `ExpressionPropagationPass` | 表达式传播 | ✅ |

### 优化器架构

```kotlin
object IrOpOptimizer {
    private val passes = listOf(
        ExpressionPropagationPass,    // 表达式传播
        AlgebraicSimplificationPass,  // 代数化简
        CopyPropagationPass,          // 拷贝传播
        DeadCodeEliminationPass,      // 死代码消除
        AccCopyPropagationPass        // ACC 拷贝传播
    )
    
    fun optimizeList(ops: List<IrOp>, liveOut: Set<FunSimCtx.RegId> = emptySet()): List<IrOp> {
        // 迭代执行所有 Pass 直到不动点
    }
}
```

---

## 当前问题

### 问题 1：函数调用合并未生效

**现象**：
```javascript
// 当前输出
_acc_ = AtkTsGlobal.print;
_acc_ = this._acc_(_acc_);

// 期望输出
_acc_ = AtkTsGlobal.print(_acc_);
```

**原因分析**：
- `CallAcc` 的 `overrideThis` 是 `THIS`，代码生成时输出为 `this._acc_()`
- 但实际应该根据 `ACC` 的来源（`ObjField.Name(GLOBAL, "print")`）生成 `AtkTsGlobal.print()`

**尝试的解决方案**：
- 在 `generateLinearBlock` 中追踪 `accSource`，当遇到 `CallAcc` 时合并为 `obj.method()` 形式
- 但方案未生效，需要进一步调试

**可能的原因**：
1. `when` 分支匹配顺序问题：第一个分支 `irOp is IrOp.AssignReg && irOp.left == FunSimCtx.RegId.ACC` 会匹配所有 ACC 赋值，包括 `CallAcc` 的情况
2. 表达式传播 Pass 可能已经修改了指令

### 问题 2：函数名和参数名丢失

**现象**：
```javascript
// 源码
function foo(i, j) { ... }

// 反编译输出
function #*#foo(FunctionObject, NewTarget, this, arg0, arg1) { ... }
```

**原因**：
- 方舟编译器使用内部编码（`#*#foo` 表示全局函数 `foo`）
- 参数名从调试信息中提取，但可能未正确解析

### 问题 3：部分字节码未实现

**现象**：
```
/* unimplemented: stglobalvar */
/* unimplemented: copyrestargs */
```

**原因**：
- ABCDE 的 `IrOp` 未覆盖所有字节码
- 需要逐步补全

---

## 下一步计划

### 优先级 P0
1. **修复函数调用合并** - 解决 `when` 分支匹配顺序问题
2. **实现函数名还原** - 从 `#*#foo` 还原为 `foo`

### 优先级 P1
3. **补全字节码支持** - 实现 `stglobalvar`、`copyrestargs` 等
4. **实现参数名还原** - 从调试信息中提取参数名

### 优先级 P2
5. **增强常量折叠** - 嵌套常量、布尔简化
6. **实现重复 Load 消除** - 消除冗余的对象字段读取

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
- 总方法数：184
- 成功：181（98.37%）
- 失败：3（均为字节码未实现）

---

## MCP 服务

### 模块位置
`modules/mcp/`

### 暴露的 Tools
| Tool | 功能 |
|------|------|
| `list_abc_classes` | 列出 ABC 文件中的所有类 |
| `decompile_class` | 反编译指定类的所有方法 |
| `search_strings` | 搜索 ABC 文件中的字符串 |

### 运行
```bash
./gradlew :modules:mcp:jvmRun
```
