# IntelliJ IDEA 插件：单文件上下文 + LLM 分析方案

## 背景与设计取向
- **使用动机**：主人想在陌生仓库中快速理解“当前打开的文件”——它做什么、关键入口/出口、调用示例，且结果需要可跳转 o(*￣︶￣*)o
- **策略转向**：不再预建全局索引，而是“按需分析”——在编辑器内选择目标文件后，把完整文件内容直接交由 Codex CLI（官方命令行工具）生成多语言描述。
- **原则对齐**：KISS（避免自建复杂静态分析）、YAGNI（仅对选中文件处理）、DRY（上下文准备逻辑复用）、SOLID（各组件单一职责，CLI 调用通过接口注入）。

> 约束：默认发送完整文件；如 Codex CLI 限制导致截断，需在 UI 中给出醒目提示喵～

## 能力边界
| 能力 | 描述 |
| --- | --- |
| 单文件触发 | 仅在用户点选/右键目标文件时运行，保证范围可控 |
| 上下文采集 | 读取完整文件内容 + 同目录/同模块的补充提示，保留原编码；必要时附带最近引用信息 |
| LLM 生成 | 通过 Codex CLI（如 `codex exec --json --model {{model}} --output-schema <schema> -`）生成“作用/关键 API/调用链线索/示例调用” |
| 结果展示 | 使用 React 构建界面，并通过 IntelliJ JCEF Browser 嵌入工具窗口/Popup，支持跳转 |
| 多语言 | Codex 负责跨语言解释；插件只需确保传输准确、行号映射可靠 |

## 非目标
- 不构建项目级静态调用图；调用链信息由 LLM 在文本层面推断。
- 不做持续后台分析，避免浪费资源 (..•˘_˘•..)
- 不替代 CLI 的身份验证/速率控制，插件只负责串联。

## 用户旅程
1. 在 Project / Editor 中选中文件 → 右键 `Analyze with ContextLens`（可配快捷键）。
2. 插件读取完整文件内容 + 附近上下文（引用/被引用文件名、所在模块、Git 历史摘要可选）。
3. 将素材组织成 Prompt，通过 `codex exec --json --output-schema ... -`（`-` 代表 STDIN）发送，要求 JSON 输出。
4. 解析 CLI 返回结果并转换为 React 需要的状态模型。
5. WebView 展示“文件卡片”，支持点击段落/按钮跳转回 IDE。

## 技术架构
```
┌────────────────────┐
│ IDEA 触发层         │（右键动作、工具窗口刷新）
└──────────┬─────────┘
           │
┌──────────▼─────────┐
│ ContextCollector    │←──统一采集上下文
└──────────┬─────────┘
           │
┌──────────▼─────────┐
│ PromptBuilder       │（模板/变量注入，保持 DRY）
└──────────┬─────────┘
           │
┌──────────▼─────────┐
│ CliInvocationBridge │→ 调用 codex CLI，处理流式输出
└──────────┬─────────┘
           │
┌──────────▼─────────┐
│ ResultParser        │（JSON/HTML → DTO）
└──────────┬─────────┘
           │
┌──────────▼─────────┐
│ InsightViewModel    │
└──────────┬─────────┘
           │
┌──────────▼─────────┐
│ React WebView (JCEF)│→ 工具窗口/Popup 渲染
└────────────────────┘
```

### 插件结构与入口
- **Action**：`com.ztianzeng.contextlens.insight.actions.AnalyzeFileAction`
  - 注册于 `plugin.xml` 的 `action` 节，放在 `ProjectViewPopupMenu` 和 `EditorPopupMenu`。
  - 执行流程：校验当前文件 → 提交后台任务 `Backgroundable`。
- **工具窗口**：`com.ztianzeng.contextlens.insight.ui.InsightToolWindowFactory`
  - 在 `ToolWindowId` 为 `ContextLens` 下创建 JCEF WebView。
  - 提供手动刷新按钮、历史列表入口。
- **项目级服务**：`FileInsightManager` 实现为 `@Service(Service.Level.PROJECT)`
  - 负责 ContextCollector、Codex 调用、结果缓存。
  - 对外暴露 `requestInsight(PsiFile)`、`getHistory()` 等 API。
- **线程模型**：
  - 读取 PSI/VirtualFile 时使用 `ReadAction.nonBlocking`。
  - Codex 调用通过 `ProgressManager.runProcessWithProgressAsynchronously`，允许取消。
  - UI 更新必须在 EDT，通过 `Application.invokeLater`+`toolWindowViewModel.update`.
- **扩展点**：`FileInsightProvider` 以 `LanguageExtensionPoint` 暴露，便于多语言上下文补充。

## 核心组件说明
- `ContextCollector`（ProjectService）：基于 PSI/VirtualFile 读取完整文件内容（保持编码），并附带同目录入口文件清单、引用关系提示。可根据语言扩展策略选择不同的 `LanguageContextContributor`。
- `PromptBuilder`：遵循模板，包含：
  - 文件摘要段（路径、模块、依赖计数）
  - 代码主体（截断/压缩，长文件可按“主要类/函数”拆段）
  - 额外提示（需要输出作用、调用链示例、跳转锚点格式）
- `CliInvocationBridge`：封装 `codex` CLI 调用（支持 `stdin`），可配置环境变量、超时时间、并发限制，支持取消。
- `ResultParser`：优先解析 Codex JSON 输出；兜底支持 HTML→JSON 容错，产出 `sections`、`references`、`lineHints`。
- `InsightToolWindow`：JCEF Browser 承载 React 单页，展示“作用说明 / 调用链推断 / 示例调用 / TODO 建议”等卡片；点击 `references` 通过消息通道调用 `NavigationBridge`。

## Prompt 与输出约定
- **Prompt 要点**：
  - 明确“你是 Codex，需要解释此文件的职责与调用关系，并输出 JSON”。
  - 传入“完整文件内容 + 相关文件提示（模块、依赖、被引用信息）”；若还有 Git 摘要则追加。
  - 指定输出 JSON Schema，如：
    ```json
    {
      "raw": "<article>..."
    }
    ```
  - 要求生成器附带“line”或“searchHint”，方便跳转。
- **字段定义**：
  | 字段 | 类型 | 说明 |
  | --- | --- | --- |
  | `raw` | string | Codex 生成的 HTML 原文（遵循 `<article>` 输出规范） |
- **多语言提示**：PromptBuilder 将 `language`、`framework`（若可推断）和编码信息注入系统提示，如 “文件语言：Rust，目标：说明模块职责并列出调用路径”，引导 Codex 输出对应语言术语。
- **输出处理**：
  - 默认要求 `--format json`；若 CLI 出现错误返回 HTML/文本，ResultParser 做容错并向用户提示。

## 多语言支持策略
- ContextCollector 对语言零感知：默认直接读取文件内容。若安装了语言扩展，可注册 `LanguageContextContributor`（如 Go 需要额外 gomod 信息）。
- PromptBuilder 在模板里添加“文件扩展名 / 语言描述”，引导 LLM 以对应语言解释。
- 只要 CLI 工具能理解输入，插件无需关心语言语法；跳转通过行号/关键字匹配实现，避免牵扯语言特定 API。

## 交互设计
- **入口**：右键动作 + 工具窗口 `刷新`，以及编辑器顶部浮层（分析完成后提示“在 React 视图中查看”）。
- **前端栈**：React + TypeScript + Vite（或 CRA）构建为静态资源；插件启动时通过 `JBCefBrowser` 加载 `index.html`，双方以 `JBCefJSQuery` / `window.intellijApi.postMessage` 通信。
- **展示**：
  - 顶部摘要卡片：文件路径、所属模块、Codex 标语、分析耗时。
  - 中部折叠面板：作用说明、调用链推断、示例调用、建议列表；使用 `CodeMirror`/`Prism` 渲染代码块。
  - 底部保留原始 Codex 响应（可折叠），方便排障。
- **跳转**：React 中的跳转按钮向 IDE 发送 `{file,line}`；IDE 侧若行号缺失则 fallback 到 `searchHint` 匹配，否则提示用户重试。

### React 构建与资源加载
- **目录结构**：
  ```
  src/
    main/
      resources/
        webview/
          contextlens/
            index.html
            assets/
              main.js
              main.css
    webview/
      package.json
      src/
        App.tsx
        components/...
  ```
- **构建流程**：
  1. WebView 代码使用 Vite 构建，命令如 `npm run build` 输出到 `dist/`。
  2. Gradle 任务 `copyWebview` 将 `webview/dist` 内容复制到 `src/main/resources/webview/contextlens/`，并在 `buildPlugin` 前执行。
  3. 开发阶段可通过 `npm run dev -- --port=4173` + `JBCefBrowser` 加载 `http://localhost:4173`，启用热更新（IDE 运行在调试模式时才允许）。
- **资源加载**：
  - 生产模式下，`JBCefBrowser` 加载 `jar://…/webview/contextlens/index.html`。
  - 通过 `JBCefCookieManager` 禁用第三方 Cookie，确保只访问本地资源。
  - 在 React 应用内注入 `window.intellijConfig`（通过 `executeJavaScript` 写入）提供初始配置。
- **依赖管理**：
  - 推荐使用 `pnpm`/`npm` 锁定版本，防止 CI 结果不一致。
  - 需要在 `package.json` 中定义 `typecheck`/`lint` 任务，CI 整体与 IDE 插件构建链路整合。
- **国际化/主题**：
  - React 读取 IDE 当前主题信息（通过消息协议），以适配暗色/亮色模式。
  - 文案以 JSON 存储，便于未来多语言扩展。
- **CI 集成**：
  - 在 Gradle `buildPlugin` 之前执行 `:webview:build`（自定义 task 调用 `npm ci && npm run build`）。
  - CI 环境需预装 Node.js（版本在 README/文档注明，如 `>=18`）；可使用 `actions/setup-node` 或相应脚本。
  - 若 `npm run build` 失败，应阻断插件打包并输出详细日志，避免发布半成品资源。
  - 提供 `npm run preview` 以在 CI 中做快照测试（例如截图比对）。

## IDE ↔ React 消息协议
- **通信通道**：基于 `window.intellijApi.postMessage(payload)`（前端→IDE）与 `browser.getCefBrowser().executeJavaScript(...)`（IDE→前端）。载荷统一为：
  ```json
  {
    "event": "insight:update",
    "requestId": "uuid",
    "body": { ... }
  }
  ```
  - `requestId` 对应一次分析任务，便于并行请求区分与取消。
- **IDE → React 事件**：
  | 事件 | 时机 | body 示例 |
  | --- | --- | --- |
  | `insight:init` | WebView 初次加载 | `{ "config": { "version":"1.0.0","settings":{...} } }` |
  | `insight:progress` | 分析运行中 | `{ "message":"调用 Codex…","percentage":45 }` |
  | `insight:update` | 分析成功 | `{ "data": <Codex JSON> }` |
| `insight:error` | CLI 失败或被用户取消 | `{ "code":"CLI_ERROR","detail":"Codex 进程中止" }` |
| `insight:history` | 用户查看历史记录 | `{ "items":[{"requestId":"..."}] }` |
- **React → IDE 事件**：
  | 事件 | 说明 | body |
  | --- | --- | --- |
  | `insight:navigate` | 请求跳转到某文件行 | `{ "file":"src/Main.kt","line":42,"requestId":"..." }` |
  | `insight:refresh` | 用户在 UI 点击刷新 | `{ "filePath":"src/Main.kt","requestId":"..." }` |
  | `insight:copyPrompt` | 复制 prompt/响应以便调试 | `{ "requestId":"..." }` |
  | `insight:openHistory` | 请求打开历史记录条目 | `{ "requestId":"...", "historyId":"2024-05-01T12:00Z" }` |
- **错误处理**：
  - IDE 收到未知事件时回复 `insight:error`，并附 `code:"UNSUPPORTED_EVENT"`。
  - WebView 若解析失败，发送 `insight:error`（body 含 `message`），IDE 显示 toast。
  - 任何事件必须包含 `requestId`；若缺失则直接丢弃并记录日志。
- **安全策略**：对 `navigate` 请求进行路径校验，禁止跳转到 IDE 之外的文件；`copyPrompt` 响应前需再次确认用户开启了调试模式。

## 配置与扩展
- `Settings > Tools > ContextLens`:
  - Codex CLI 路径、模型、Token 文本 / API URL；CLI 调用固定为 `codex exec --json --model <model> --output-schema <temp> -`，Schema 临时文件由插件自动生成并销毁。
  - 由于 Codex 要求 schema 声明 `"additionalProperties": false`，插件生成的临时 schema 会自动加上该约束，避免 400 报错。
  - 是否附带 Git diff、是否拼接同目录引用、最大附加提示长度等。
- 内置 Prompt：插件在同步 `AGENTS.md` 时写入固定的仓库说明文案，不再暴露可编辑入口，保证不同项目行为一致。
- 通过 `FileInsightProvider` 接口允许第三方实现自定义 Prompt/Parser；前端通过消息协议可加载额外 React 卡片。

### 配置持久化设计
- **状态管理**：使用 Kotlin `data class FileInsightState` + `PersistentStateComponent<FileInsightState>`；字段示例：
  ```kotlin
  data class FileInsightState(
      var codexPath: String = "codex",
      var codexModel: String = "code-navigator",
      var apiKey: String = "",
      var apiUrl: String = "",
      var maxConcurrency: Int = 2,
      var analysisOutputRoot: String = ".analysis",
      var overwriteExistingOutput: Boolean = true,
      var enableCliLogs: Boolean = true,
      var enableGitDiff: Boolean = true
  )
  ```
- **设置界面**：实现 `Configurable`，分为三个分组：
  1. Codex：CLI 路径、模型、API Key/API URL、并发限制。
  2. 输出与日志：配置 `.analysis` 输出根目录、是否自动覆盖旧的分析 HTML、是否写入 CLI 日志。
- **变更监听**：`FileInsightSettings` 作为 `Topic<FileInsightSettingsListener>` 发布配置变化，`CliInvocationBridge` 等组件订阅以更新运行参数。
- **目录管理**：初始化时检查 `analysisOutputRoot` 是否存在，若不存在则创建（尊重 `.gitignore`），并在输出前递归创建与原始源码相同的子目录结构。
- **安全提醒**：当 `apiKey` 文本或 `CODEX_API_KEY` 未配置时，在工具窗口提示“需配置凭证”；必要时提示用户校验来源与权限。
- **凭证校验流程**：
  1. 打开设置页即尝试读取 `apiKey`/环境变量，失败则在输入框下方标红提示。
  2. “测试连接”按钮会在后台读取 Token 文本并以 `CODEX_API_KEY=<value> codex version` 验证 CLI；失败则展示 CLI `stderr` 并建议检查网络/权限。
  3. `loadState()` 时如果凭证缺失，向通知中心推送“配置 Codex 凭证”消息（附跳转按钮）。
- **输出写入策略**：生成结果后立即将 HTML 写入 `${analysisOutputRoot}/<relative-source-path>.html`。若上一次分析产物存在则覆盖，避免额外的 snapshots/history 目录；该行为由存储设置中的“自动覆盖”开关控制。

## Codex CLI 协议
- **二进制定位**：默认命令为 `codex`（可在设置中改为绝对路径）；插件在首次运行时执行 `codex version` 验证二进制可用性。
- **鉴权与环境变量**：
  - 需要 `CODEX_API_KEY`；插件会优先读取设置中的 Token 文本并注入该环境变量，若缺失则回退到系统环境。
  - 支持配置 `CODEX_REGION` 或 `codex --region <id>`，方便路由至就近节点。
- **标准命令格式**（插件内所有 CLI 调用的基线）：
  ```bash
  tmp_home=$(mktemp -d) && CODEX_API_KEY="sk-gGzeEMEpdfdtMT3zvylentgdnr3p1mNN" \
  OPENAI_BASE_URL="https://new.xychatai.com/codex/v1" HOME="$tmp_home" XDG_CONFIG_HOME="$tmp_home/.config" \ 
  codex exec "分析build.gradle.kts的内容" -o 分析结果.html
  ```
- 命令中通过 `tmp_home` 构建临时隔离环境，后续实现阶段需将其替换为持久化目录 `~/.codex/analysis/`，以便复用缓存与凭证。
- `~/.codex/analysis/` 目录需要在调用前写入最新的 `AGENTS.md`（由插件生成/同步），确保 Codex 获取到准确的 Agent 配置。
- `-o 分析结果.html` 为当前的临时输出文件，真正落地时要改成“项目相对路径”的结果文件（例如 `./analysis-output/分析结果.html`），方便 IDE 读取与归档。
- **错误与退出码**：
  - 退出码 `0`：成功；`1`：参数/鉴权错误；`2`：速率限制；`3`：内部错误。
  - 若 CLI 写出 JSON 错误结构：
    ```json
    {"status":"error","code":"RATE_LIMIT","message":"…","retryAfter":10}
    ```
    插件需根据 `retryAfter` 控制退避。
- **超时与并发**：
  - IDE 不再强制设置调用超时，若耗时较长可手动取消任务（取消后插件强制终止 CLI）。
  - 采用队列限流：默认同时只允许 2 个 Codex 请求；其余排队并在 UI 中显示等待状态。
- **日志**：
  - CLI 的 `stderr` 会被捕获写入 `.analysis/logs/last-run.log`（可配置），敏感内容前置脱敏。
  - debug 模式下可开启 `--verbose`，但需提示可能包含代码片段。

## 数据存储策略
- **源文件位置**：始终读取 IDEA 当前项目中的真实文件路径（例如 `/your/project/src/.../Foo.java`），不在插件中复制或移动原文件，确保 “Never break userspace”。
- **分析输出目录**：不再维护 `contextlens` 下的 snapshots/history，统一在项目根 `.analysis/` 中按源码路径镜像输出 HTML。示例：分析 `/project/src/Main.java` 时写入 `/project/.analysis/src/Main.java.html`，若上层目录不存在则递归创建。
- **产物内容**：每个 HTML 文件包含 Codex 的原始输出与可回溯的 CLI 命令信息，便于在 IDE 或外部工具中直接查看。
- **临时文件**：调用 Codex CLI 时使用系统临时目录（如 macOS `/var/folders/.../codex-insight/tmp-xxx`）写入一次性文件，CLI 结束即删除，防止泄漏。
- **结果复用**：插件刷新数据时优先尝试读取 `.analysis/<relative>.html`，若命中则直接加载已有 HTML，而无需额外历史目录或冗余快照。

## 关键风险与缓解
| 风险         | 对策                                                             |
|------------|----------------------------------------------------------------|
| CLI 失败/超时  | 明确错误栈，允许用户重试或查看原始日志                                            |
| LLM 输出不结构化 | 在 Prompt 中强制 JSON，ResultParser 支持容错（JSON5、HTML code block） |
| 敏感代码外发     | 提供“红线警示”与路径黑名单，必要时加脱敏（如只发类名+1~2段代码）                            |
| 大文件超 Token | ContextCollector 预先分段 + 自动摘要，或允许用户标记“仅分析选中范围”                  |

## 迭代路线
1. **MVP**：单文件触发 → CLI → HTML 展示；无行号跳转。
2. **增强版**：结构化 JSON、line hint、折叠面板、可自定义 Prompt。
3. **高级版**：上下文策略插件化、批量分析队列、历史缓存。
4. **智能版**：支持增量 diff 分析、与 PR 审查/文档生成链路协同。

## 验证策略
- 在多语言样本仓库（Java/Kotlin/Go/TS 等）进行手工验证，确认 CLI 输出质量。
- 针对大文件编写单元测试模拟上下文截断逻辑，保证不会超出 CLI 限制。
- UI 层使用 `com.intellij.testFramework.fixtures` 验证工具窗口交互与跳转稳定性。

> 该方案保持插件层轻量，核心分析交由 CLI LLM，既满足主人“范围可控”的需求，也便于后续替换不同模型 ฅ'ω'ฅ
