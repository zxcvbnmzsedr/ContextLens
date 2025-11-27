# ContextLens

ContextLens 是一款 IntelliJ 平台插件与 React WebView 组合方案，为主人提供“所见即所得”的单文件 LLM 分析体验：从编辑器中选中任意源码文件，插件会收集局部上下文并通过 Codex CLI 请求结构化洞察，然后在 IDE 内嵌 WebView 中呈现可跳转的可视化解读。

## 新名字说明
- **命名理由**：项目目前聚焦“针对单个文件拉取上下文并投射成洞察”，ContextLens（上下文之镜）突出了“局部放大”、“无需预建索引”的核心价值，同时保留未来扩展为多语言、多上下文的空间。
- **兼容性**：源码包、插件 ID、ToolWindow 以及设置项等均统一为 `ContextLens`，防止遗留双重命名。

## 功能亮点
- **按需分析**：`Analyze with ContextLens` 动作只在主人选中文件时触发，避免昂贵的全局索引或后台扫描。
- **统一上下文采集**：`ContextCollector` 读取文件内容、所属模块、同目录邻居文件与 Git 修订，保持编码与路径完整性。
- **Codex CLI 桥接**：`CliInvocationBridge` 负责生成 schema、注入凭证、流控与超时管理，并自动同步 `AGENTS.md`。
- **可视化洞察**：React + Vite WebView 通过 JCEF 嵌入 IDEA 工具窗口，展示作用说明、调用链、示例调用与历史列表，并提供跳转回编辑器的能力。
- **持久化输出**：分析结果与 CLI 日志写入 `.analysis/`（与源码路径镜像），便于复用、离线查看与排障。

## 技术架构
```
┌────────────┐  右键动作/工具窗口
│ IDEA Layer │───► ContextCollector ──► PromptBuilder
└────┬───────┘                          │
     │                                   ▼
     │                           CliInvocationBridge
     │                                   │
     ▼                                   ▼
┌────────────┐  Publish  ┌─────────────────────┐
│ MessageBus │──────────►│ React WebView (JCEF)│
└────────────┘  Events   └─────────────────────┘
```
- Kotlin 层各组件遵循单一职责：采集、调用、存储、事件、设置分离，确保易于测试与替换。
- WebView 通过 `window.intellijApi.postMessage` 与 IDE 事件桥通讯，以 `requestId` 区分多次分析流程。

## 目录速览
| 路径                                                       | 描述                                            |
|----------------------------------------------------------|-----------------------------------------------|
| `src/main/kotlin/com/ztianzeng/contextlens/insight/actions` | IDEA 动作与入口                                    |
| `src/main/kotlin/com/ztianzeng/contextlens/insight/core`    | ContextCollector、Codex 桥接、存储等核心服务             |
| `src/main/resources`                                     | `plugin.xml`、`prompts/AGENTS.md` 以及嵌入式 Web 资源 |
| `webview/`                                               | React + Vite WebView 源码                       |
| `docs/idea-call-context-plugin.md`                       | 设计文档、场景说明                                     |

## 快速开始
1. **准备开发环境**
   - IntelliJ IDEA 2023.3+（Ultimate 更佳）
   - JDK 17（Gradle Wrapper 自动下载依赖）
   - Node.js 18+ 与 `pnpm`（前端开发）
   - Codex CLI（可通过 `codex version` 验证），并确保 `CODEX_API_KEY` 可用
2. **拉取依赖**
   ```bash
   pnpm install --dir webview
   ```
3. **构建前端**
   ```bash
   cd webview
   pnpm build        # 产物将输出到 webview/dist
   ```
4. **同步静态资源并运行 IDE**
   ```bash
   ./gradlew copyWebview   # 将 dist 拷贝到 src/main/resources/webview/contextlens
   ./gradlew runIde        # 启动沙箱 IDEA 进行调试
   ```
5. **触发分析**
   - 在沙箱工程内右键文件 → `Analyze with ContextLens`
   - 在 `ContextLens` 工具窗口查看结果并跳转

## 前后端开发流程
- **IDE 插件**
  - `./gradlew test`：运行 Kotlin 单元测试（ContextCollector、CliInvocationBridge、Storage 等）。
  - `./gradlew buildPlugin`：全量构建并打包插件 ZIP，内部会自动执行 `copyWebview`。
  - 调试 WebView 时可在 `idea64.vmoptions` 或运行配置中注入 `-Dcontextlens.webview.devserver=http://localhost:4173`。
- **WebView**
  - `pnpm dev --dir webview -- --port=4173`：启用 HMR，与 `runIde` 中的 dev server 配置联动。
  - `pnpm lint --dir webview`：执行 ESLint/TS 检查，保证 React 栈遵循仓库规范。
  - `pnpm preview --dir webview`：构建后本地预览 JCEF 渲染效果。

## Codex CLI 配置
- 在 `Settings > Tools > ContextLens` 中设置：
  - `Codex path`（默认 `codex`）、`Model`、`API Key`/`API URL`
  - 并发限制、请求超时、`.analysis` 输出路径、是否覆盖旧结果、是否记录 CLI 日志
- 插件会在 `%USER_HOME%/.codex/analysis/` 下写入最新的 `AGENTS.md`，确保 CLI 调用共享一致提示词。
- 若 CLI 返回错误或超时，工具窗口会显示 `insight:error` 并引导主人查看 `.analysis/logs/last-run.log`。

## 验证建议
- **IDE 端**：`./gradlew test`（验证上下文采集与存储逻辑）、`./gradlew runIde`（手动测试）  
- **WebView 端**：`pnpm lint` + 手动在 dev server 中对多主题、长响应场景做走查  
- **CLI**：本地执行 `codex exec --model <model> -o /tmp/out.json - < sample_request.json`，确认凭证及网络环境

## 未来路线
- 按需扩展 `LanguageContextContributor`，为特定语言追加模块提示
- 通过 `FileInsightProvider` 插件点支持多 Prompt/Parser
- 增加历史搜索、批量分析队列与 diff 模式，保持 ContextLens 的“轻量 + 可解释”定位

---
以 ContextLens 为新名字，仓库文档、插件市场条目与说明文件都可以统一口径，凸显“聚焦上下文的放大镜”价值。如需进一步更名（包名、资源路径等），请先在 issue 中同步团队，确保兼容性与发布节奏。
