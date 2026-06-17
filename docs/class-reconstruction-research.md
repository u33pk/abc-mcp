# ArkTS Class 重组调研与实现规划

> 状态：**已实现**。核心代码位于 `modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/decompiler/structure/reconstruction/`，MCP 工具 `reconstruct_class` 已可用。

## 1. 背景与问题

当前 MCP 的 `get_class_detail` 工具返回的是 **ABC 文件中的顶层 `AbcClass` 信息**。但在 ArkCompiler 的编译模型里，一个 `.ets` 源文件本身被编译成一个模块记录（module record），而文件内部定义的 `class` / `struct` / `@Component` 并不是顶层 `AbcClass`，而是被编码在模块入口函数 `func_main_0` 内部的 `defineclasswithbuffer` 指令里。

这导致：

- `get_class_detail` 看到的“类”其实是**文件模块**，字段是 `pkgName`、`isCommonjs` 等模块元数据。
- 真正的 ArkTS 类（如 `Index extends ViewPU`）在反编译输出里被拆成了 `func_main_0` 中的一堆 `defineclasswithbuffer`、`definemethod`、`Object.defineProperty` 操作。
- 方法列表里混入大量模块级辅助函数、匿名函数、UI builder 生成函数，无法直接对应到源代码中的类成员。

因此需要做一个 **Class 重组（Class Reconstruction）** 阶段：从 `func_main_0` 里把 `defineclasswithbuffer` 块识别出来，还原成可读的 `class X extends Y { ... }` 形式，并在概览工具中展示真正的类结构。

---

## 2. ArkCompiler 的模块模型

### 2.1 `func_main_0` 是编译器生成的模块入口

项目代码中已明确把 `func_main_0` 视为模块入口：

```kotlin
// modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/cfm/ClassItem.kt
class AbcClass(...) {
    companion object {
        const val ENTRY_FUNC_NAME = "func_main_0"
    }
}

fun AbcClass.entryFunction() = methods.firstOrNull { it.name == AbcClass.ENTRY_FUNC_NAME }
```

每个 `.ets` / `.ts` / `.js` 源文件编译后对应一个 `AbcClass`，并带有一个 `func_main_0` 方法。运行时加载模块就是执行这个方法。

### 2.2 `func_main_0` 里到底做了什么？

以 `entry-default-unsigned.hap` 中的 `&entry/src/main/ets/pages/Index&` 为例，其 `func_main_0` 反编译/反汇编显示它至少包含：

1. **顶层函数定义**：如 `defaultAdvancedDnsConfig`、`defaultQuerySummary` 等。
2. **常量初始化**：如 DNS 过滤列表预设数组。
3. **词法环境变量**：大量 `stlexvar`（当前未实现，输出为注释）。
4. **真正的类定义**：`defineclasswithbuffer` 创建 `Index` 类。
5. **属性 getter/setter**：用 `definemethod` 生成后通过 `Object.defineProperty` 挂到 prototype。
6. **import/export 绑定**：解析依赖、写入 `localExports`。
7. **其他顶层语句**：组件注册、路由绑定等。

### 2.3 真正的类在哪里？

在反汇编中可以看到：

```asm
[0x037c] defineclasswithbuffer ?, 54, this.#~@0=#Index,
         { 13 [
             str:"setInitiallyProvidedValue",
             Method:&entry/src/main/ets/pages/Index&.#~@0>#setInitiallyProvidedValue,
             MethodAffiliate:1,
             ...
         ]}, 3, v57
```

- 类名：`#Index`
- 父类：`v57`，往前追溯为 `AtkTsGlobal.ViewPU`
- 方法列表：直接编码在 class literal buffer 里
- 属性：后续通过 `definemethod` + `Object.defineProperty` 成对添加

所以源代码中的：

```arkts
@Entry
@Component
struct Index {
  @State currentTab: number = 0
  // ...
}
```

在 ABC 里会被编译成 `func_main_0` 里的一段 class 创建逻辑，而不是一个独立的顶层类。

---

## 3. 当前 `get_class_detail` 的局限

以同一个文件为例，`get_class_detail` 输出：

```
Class: &entry/src/main/ets/pages/Index&
Fields: 6
  pkgName@entry
  isCommonjs
  hasTopLevelAwait
  isSharedModule
  scopeNames
  moduleRecordIdx
Methods: 1814
  anon_307edc5f
  anon_f1cbca46
  ...
```

这 1814 个方法里既包含真正的 `Index` 类成员（如 `aboutToBeDeleted`、`updateStateVars`），也包含大量模块级匿名函数、UI builder 生成的 `forEachItemGenFunction`、`deepRenderFunction` 等。字段列表则完全是模块记录字段，和 `@State` 等类字段无关。

**结论**：当前工具只能给出“文件级”概览，不能给出“类级”概览。

---

## 4. Class 重组的目标

### 4.1 最终要输出什么？

在反编译层面，希望把 `func_main_0` 中的 class 创建逻辑重组成：

```typescript
class Index extends ViewPU {
    currentTab: number;
    busy: boolean;
    stopping: boolean;
    // ...

    constructor() { ... }

    setInitiallyProvidedValue() { ... }
    updateStateVars() { ... }
    purgeVariableDependenciesOnElmtId() { ... }
    aboutToBeDeleted() { ... }
    // ...
}
```

在 MCP 工具层面，希望 `get_class_detail`（或新增 `reconstruct_class`）能返回：

```
Class: Index
Super: ViewPU
Source file: entry/src/main/ets/pages/Index.ets
Fields:
  currentTab: number
  busy: boolean
  stopping: boolean
  ...
Methods:
  constructor
  setInitiallyProvidedValue
  updateStateVars
  purgeVariableDependenciesOnElmtId
  aboutToBeDeleted
  ...
```

### 4.2 必须保留非类代码

`func_main_0` 里除了 class 创建，还有 import、顶层变量、常量数组、组件注册等逻辑。重组时**不能把整个 `func_main_0` 替换成一个 class**，只能把识别出的 class 块抽出来，其余部分保持原样输出。

---

## 5. 技术方案

### 5.1 输入与触发条件

- 入口：模块的 `func_main_0`（`AbcClass.entryFunction()`）。
- 触发：扫描方法体中的 `defineclasswithbuffer` / `defineclass` 指令。
- 一个 `func_main_0` 可能包含多个 class（多 class 文件），也可能一个都没有（纯函数模块）。

### 5.2 需要解析的信息

对每一条 `defineclasswithbuffer`：

| 信息 | 来源 |
|------|------|
| 类名 | 指令中的类名字符串（如 `#Index`），需要解码 `#~@0=#Index` 这种前缀 |
| 父类 | 指令操作数中的父类寄存器，需要前向/后向数据流追踪 |
| 构造器 | class literal buffer 中标记为 `MethodAffiliate:0` 的方法 |
| 实例方法 | class literal buffer 中的普通 `Method` 项 |
| 静态方法 | 后续 `definemethod` 中 HomeObject 为 class 对象（而非 prototype）的项 |
| 字段/属性 | 成对出现的 `definemethod` getter/setter，通过 `Object.defineProperty` 挂到 prototype |
| 方法参数与签名 | 从对应 `MethodItem` 的 `proto` 和 `DebugInfo` 读取 |

### 5.3 与现有模块的集成点

- **IR 层**：在 `modules/abcde/.../behaviour/IrOp.kt` 中已有 `NewClass` 等 IR 节点，但当前渲染为 `/* newClass */` 占位。需要增强 IR 以保留 class literal buffer 的元数据。
- **反编译器**：`StructuredToJs.kt` 在生成 `func_main_0` 时，可以新增一个 **ClassReconstructionPass**，把连续的 class 创建语句提取为 `ClassDeclaration` 语句节点。
- **类层次结构**：已有的 `ClassHierarchyIndex` 已经尝试从 `NewClass` 父寄存器回溯 extends 关系，可以直接复用。
- **MCP 工具**：`get_class_detail` 可以增加一个模式：除了返回顶层 `AbcClass`，还扫描 `func_main_0` 中的 `defineclasswithbuffer`，返回内部类列表。也可以新增 `reconstruct_class` 工具专门做这件事。

### 5.4 输出形式建议

推荐分两个阶段暴露能力：

1. **增强 `get_class_detail`**：在现有输出末尾追加一个 `Reconstructed Classes` 区块，列出 `func_main_0` 中发现的类（名称、父类、字段、方法名）。
2. **新增 `reconstruct_class` / 增强 `decompile_class`**：输出完整的 class 语法，包含方法体。

---

## 6. 实施阶段

### Phase 1：指令与 buffer 解析
- [ ] 确认 `defineclasswithbuffer` / `defineclass` 的 IR 表示，保留 class literal buffer 的原始数据。
- [ ] 解析 buffer 中的方法列表、方法名、`MethodAffiliate` 标志。
- [ ] 实现父类寄存器回溯（已有 `ClassHierarchyIndex` 可参考）。

### Phase 2：属性/字段识别
- [ ] 识别 `definemethod` + `Object.defineProperty` 成对模式。
- [ ] 区分 getter/setter 与普通方法。
- [ ] 将 getter/setter 对合并为类字段，标注类型（若可从方法体推断）。

### Phase 3：class 语法生成
- [ ] 新增 `ClassDeclaration` Statement 类型。
- [ ] 在 `StructuredToJs.kt` 中实现 class 块渲染。
- [ ] 确保非 class 顶层代码不被吞掉。

### Phase 4：MCP 工具集成
- [ ] 增强 `get_class_detail`：扫描 `func_main_0` 并输出内部类概览。
- [ ] 新增 `reconstruct_class` 工具，返回完整 class 代码。
- [ ] 添加单元测试（`es2abc` 编译简单 class）和 HAP 集成测试。

### Phase 5：复杂场景
- [ ] 多 class 文件。
- [ ] 继承链（`extends`）。
- [ ] 实现接口（`implements`，如果有对应指令）。
- [ ] `@Component struct` 与 `ViewPU` 的特殊处理。
- [ ] 静态字段与方法。

---

## 7. 风险与依赖

| 风险 | 说明 | 缓解 |
|------|------|------|
| 父类寄存器追踪失败 | `defineclasswithbuffer` 的父类可能经过多步赋值 | 复用/增强 `ClassHierarchyIndex` 的回溯能力 |
| getter/setter 模式多样 | ArkCompiler 可能用不同指令序列定义属性 | 先覆盖最常见模式，其余保持原样 |
| 多 class 文件边界不清 | 多个 `defineclasswithbuffer` 可能穿插顶层语句 | 以 class 创建指令为锚点，只提取其连续相关语句 |
| 与 `definemethod` 当前实现冲突 | `definemethod` 目前作为 NOP 透传 | 需要让它在 class 上下文中有语义 |
| 类型信息缺失 | 字段类型不一定保存在字节码中 | 输出时允许类型为 `any` 或从 setter 推断 |

---

## 8. 相关代码索引

| 文件 | 作用 |
|------|------|
| `modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/cfm/ClassItem.kt` | `ENTRY_FUNC_NAME = "func_main_0"`、`entryFunction()` |
| `modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/decompiler/structure/StructuredToJs.kt` | 代码生成，可插入 class 重组 pass |
| `modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/decompiler/behaviour/IrOp.kt` | IR 节点，含 `NewClass` |
| `modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/xref/ClassHierarchyIndex.kt` | 父类/extends 回溯 |
| `modules/mcp/src/jvmMain/kotlin/me/yricky/oh/mcp/tools/GetClassDetailTool.kt` | class 概览工具 |
| `modules/mcp/src/jvmMain/kotlin/me/yricky/oh/mcp/tools/DecompileMethodTool.kt` | 方法反编译工具 |

---

## 9. 示例：期望输出

### 当前 `decompile_method(func_main_0)` 输出片段

```javascript
function func_main_0(FunctionObject, NewTarget, this) {
    v0 = FunctionObject;
    ...
    v57 = AtkTsGlobal.ViewPU;
    v58 = /* newClass */.prototype;
    v59 = "currentTab";
    v60 = undefined;
    v61 = /* newClass */.prototype;
    Object.defineProperty(v58, v59, {get: v61, set: v60});
    ...
}
```

### 重组后期望输出

```typescript
class Index extends ViewPU {
    currentTab: number;
    busy: boolean;
    stopping: boolean;
    rulesDirty: boolean;
    ...

    constructor() { ... }

    setInitiallyProvidedValue() { ... }
    updateStateVars() { ... }
    purgeVariableDependenciesOnElmtId() { ... }
    aboutToBeDeleted() { ... }
}
```

其余 `func_main_0` 中的 import、顶层变量、常量数组等代码保持原样。
