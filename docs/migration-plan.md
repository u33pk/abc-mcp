# xpanda 反编译算法移植计划

## 目标
将 xpanda 的结构化分析算法移植到 ABCDE，构建 MCP 服务供 LLM 调用。

## 核心原则
- **只移植算法**：DominatorGraph + LoopFinder + StructureAnalysis（~900行）
- **完全重写适配层**：Region/Statement/CodeGenerator，适配 ABCDE 的 IrOp
- **抛弃重型依赖**：Guava、GraalJS、Node.js、RxJava 全部不用

## 模块结构

```
modules/abcde/src/commonMain/kotlin/me/yricky/oh/abcd/decompiler/
├── behaviour/          # 已有 - IrOp IR 中间表示
├── CodeSegment.kt      # 已有 - CFG 基本块定义
├── Linearizer.kt       # 已有 - 旧的模式匹配线性化器
├── ToJs.kt             # 已有 - 旧的代码生成器
├── structure/          # 新增 - 结构化分析核心
│   ├── RegionGraph.kt      # 轻量图数据结构（替代 Guava MutableValueGraph）
│   ├── Region.kt           # 区域节点定义（Linear/Condition/Tail）
│   ├── DominatorGraph.kt   # 支配树 + 支配边界
│   ├── LoopFinder.kt       # 基于支配关系的循环检测
│   ├── RegionGraphBuilder.kt  # CFG → RegionGraph 转换
│   ├── StructureAnalysis.kt   # 核心规约算法
│   └── statement/          # 语句类型
│       ├── Statement.kt        # 语句接口
│       ├── IfStatement.kt      # if/then/else
│       ├── WhileStatement.kt   # while 循环
│       ├── DoWhileStatement.kt # do-while 循环
│       ├── LogicalExpression.kt # && / || 短路
│       └── TryCatchStatement.kt # try-catch（后期）
├── StructuredDecompiler.kt  # 新增 - 统一入口 API
└── StructuredToJs.kt       # 新增 - 基于结构化的代码生成

modules/mcp/                # 新增 - MCP 服务模块
├── build.gradle.kts
└── src/jvmMain/kotlin/me/yricky/oh/mcp/
    └── Main.kt             # MCP Server 入口（stdio JSON-RPC）
```

## 实施阶段

### Phase 1：基础设施 ✅
- [x] 分析两个项目架构
- [x] 创建 `structure/` 包
- [x] 实现 `RegionGraph<T>` 轻量图
- [x] 移植 `DominatorGraph.kt`
- [x] 移植 `LoopFinder.kt`

### Phase 2：结构化分析 ✅
- [x] 实现 `Region.kt`
- [x] 实现 `RegionGraphBuilder.kt`（连接 ABCDE CFG → Region）
- [x] 移植 `StructureAnalysis.kt` 核心算法

### Phase 3：代码生成 ✅
- [x] 实现 Statement 类型
- [x] 实现 `StructuredToJs.kt`
- [x] 实现 `StructuredDecompiler.kt` 统一入口

### Phase 4：MCP 服务 ✅
- [x] 创建 `modules/mcp` 模块
- [x] 实现 MCP Server（stdio JSON-RPC）
- [x] 定义 Tools（list_abc_classes, decompile_class, search_strings）

### Phase 5：集成测试与优化（待完成）
- [ ] 单元测试
- [ ] try-catch 支持
- [ ] async/generator 支持
- [ ] 性能优化

## 依赖对比

| xpanda 依赖 | ABCDE 替代方案 |
|-------------|----------------|
| Guava MutableValueGraph | 自定义 RegionGraph<T>（~100行） |
| GraalJS + dist.js | Kotlin 字符串拼接 |
| Node.js cmd.js | 移除 |
| RxJava | 顺序执行 |
| Lombok | Kotlin 数据类 |

## MCP Tools

| Tool | 输入 | 输出 |
|------|------|------|
| `list_abc_classes` | abc_path | 类列表 |
| `decompile_class` | abc_path, class_name | 反编译代码 |
| `search_strings` | abc_path, pattern | 搜索结果 |

## 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| try-catch 处理复杂 | Phase 1-3 先支持无异常方法，后期再加 |
| IrOp 缺失 opcode | 降级为注释输出，逐步补全 |
| 不可规约循环 | 移植 fixIrreducibleByTailRegion 策略 |
| 大方法超时 | 继承 500 blocks / 3000 iterations 保护 |

## 使用方式

### 编译
```bash
./gradlew :modules:mcp:compileKotlinJvm
```

### 运行 MCP Server
```bash
./gradlew :modules:mcp:jvmRun
```

### MCP 配置（Claude Desktop）
```json
{
  "mcpServers": {
    "abcde": {
      "command": "java",
      "args": ["-jar", "/path/to/abcde-mcp.jar"]
    }
  }
}
```
