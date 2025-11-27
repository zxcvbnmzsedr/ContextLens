# Repository Guidelines

## Project Structure & Module Organization
仓库由 IntelliJ 平台插件主工程与 React WebView 组成：
`src/main/kotlin/com/ztianzeng/contextlens` 含 Action、Project Service、UI Bridge 等 Kotlin 代码
`src/main/resources` 承载 `plugin.xml` 与内嵌静态资源
`src/test` 预留给 IDE 测试夹具
`webview/src` 为工具窗口前端
`docs/` 保存设计文档（例如 `idea-call-context-plugin.md`）
构建产出依赖 `webview/dist` 被复制到 `src/main/resources/webview/contextlens`，请在提交前确认前端编译品已同步。

## Build, Test, and Development Commands
使用 `./gradlew runIde` 启动沙箱 IDEA 并在调试时可通过 `-Dcontextlens.webview.devserver=http://localhost:4173` 指向本地 WebView。
`./gradlew buildPlugin` 负责全量构建，在内部依赖 `copyWebview` 将 Vite 产物入包；
常规单元测试使用 `./gradlew test`。
前端需在 `webview/` 下运行 `pnpm install`（或 `pnpm i`）初始化，再执行 `pnpm dev` 进行热更新、`pnpm build` 生成 `dist/`、`pnpm lint` 触发 ESLint/TS 检查。

## Coding Style & Naming Conventions
Kotlin 采用 JetBrains 官方风格：4 空格缩进、`PascalCase` 类型名、`camelCase` 成员，包命名保持 `com.ztianzeng.contextlens.<module>`，文件内按单一职责拆分并坚持 SOLID/KISS。React/TypeScript 遵循 ESLint 规则（见 `webview/eslint.config.js`），组件命名为 `PascalCase`，hooks 使用 `useX` 前缀，样式与常量集中在各自模块，避免硬编码 CLI 命令。

## Testing Guidelines
目前尚无现成测试用例，新增功能需补充：Kotlin 侧在 `src/test/kotlin` 使用 `com.intellij.testFramework.fixtures` 或 JUnit5 断言主要服务（ContextCollector、CliInvocationBridge）行为；WebView 可借助 `pnpm lint` 与 screenshot/preview 任务确保渲染一致。提交前至少执行一次 `./gradlew test` 与 `pnpm lint`，并在 PR 说明中标注测试覆盖及手动验证场景。

## Commit & Pull Request Guidelines
现有历史非常精简（如 `init`、`1`），后续提交请继续保持简短命令式标题（≤50 字符），必要时追加描述区分前端/插件模块。PR 描述应包含：变更目的、涉及目录（例如 `src/main/kotlin/...` 或 `webview/src`）、测试结果、关联 issue/任务编号以及需要截图的 UI 改动。若修改触达 Codex CLI 配置或安全敏感路径，请在 PR 中突出说明以便快速审阅。

## Security & Configuration Tips
Codex CLI 依赖 `CODEX_API_KEY` ；
推荐将 `.analysis/` 目录保持在 `.gitignore` 中。运行 `codex` 前确认可执行文件在 PATH 内，若需自定义路径请通过设置页更新并在说明中记录，确保其他贡献者可复现。
