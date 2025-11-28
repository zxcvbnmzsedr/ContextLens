package com.ztianzeng.contextlens.insight.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import javax.swing.JPanel

class InsightWebviewPanel(project: Project) : Disposable {
    private val url = resolveWebviewUrl()
    private val browser = JBCefBrowser(url)
    private val bridge = InsightWebviewBridge(project, browser)

    val component = JPanel(BorderLayout()).apply {
        add(browser.component, BorderLayout.CENTER)
        background = UIUtil.getPanelBackground()
    }

    override fun dispose() {
        bridge.dispose()
        browser.dispose()
    }

    companion object {
        private val log = Logger.getInstance(InsightWebviewPanel::class.java)
        private val packagedWebviewDir: Path? by lazy { locatePackagedWebviewDir() }
        @Volatile private var staticServerUrl: String? = null
        @Volatile private var staticServer: HttpServer? = null

        private fun resolveWebviewUrl(): String {
            System.getProperty("contextlens.webview.devserver")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            val root = packagedWebviewDir
            if (root == null) {
                log.warn("ContextLens webview resources are missing; falling back to blank page")
                return "about:blank"
            }
            val base = ensureStaticServer(root) ?: return "about:blank"
            return "$base/index.html"
        }

        private fun locatePackagedWebviewDir(): Path? {
            val base = InsightWebviewPanel::class.java.classLoader.getResource("webview/contextlens")
            if (base == null) {
                log.warn("ContextLens webview resource directory is missing from the classpath")
                return null
            }
            return when (base.protocol) {
                "file" -> runCatching { Paths.get(base.toURI()) }.onFailure {
                    log.warn("Failed to resolve file-based webview resources", it)
                }.getOrNull()
                "jar" -> extractJarDirectory(base)
                else -> {
                    log.warn("Unsupported protocol for webview resources: ${base.protocol}")
                    null
                }
            }
        }

        private fun extractJarDirectory(base: URL): Path? {
            return runCatching {
                val connection = base.openConnection() as JarURLConnection
                val jarFile = connection.jarFile
                val entryPrefix = connection.entryName.trimEnd('/') + "/"
                val tempDir = Files.createTempDirectory("contextlens-webview")
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.startsWith(entryPrefix)) continue
                    val relative = entry.name.removePrefix(entryPrefix)
                    if (relative.isEmpty()) continue
                    val target = tempDir.resolve(relative)
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        jarFile.getInputStream(entry).use { input ->
                            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
                tempDir.toFile().deleteOnExit()
                jarFile.close()
                tempDir
            }.onFailure {
                log.warn("Failed to extract webview assets from plugin JAR", it)
            }.getOrNull()
        }

        private fun ensureStaticServer(root: Path): String? {
            staticServerUrl?.let { return it }
            synchronized(this) {
                staticServerUrl?.let { return it }
                val index = root.resolve("index.html")
                if (!Files.exists(index)) {
                    log.warn("ContextLens webview index.html not found under $root")
                    return null
                }
                val server = startStaticServer(root) ?: return null
                staticServer = server
                val port = (server.address as InetSocketAddress).port
                return "http://127.0.0.1:$port".also { staticServerUrl = it }
            }
        }

        private fun startStaticServer(root: Path): HttpServer? {
            return runCatching {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    try {
                        handleRequest(exchange, root)
                    } catch (e: Exception) {
                        log.warn("Failed to serve webview asset ${exchange.requestURI}", e)
                        exchange.sendResponseHeaders(500, -1)
                    } finally {
                        exchange.close()
                    }
                }
                server.executor = Executors.newCachedThreadPool()
                server.start()
                Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
                log.info("ContextLens webview server started on ${server.address}")
                server
            }.onFailure {
                log.warn("Failed to start ContextLens webview server", it)
            }.getOrNull()
        }

        private fun handleRequest(exchange: HttpExchange, root: Path) {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                return
            }
            val requestedPath = exchange.requestURI.path.orEmpty().removePrefix("/")
            val relativePath = if (requestedPath.isBlank()) "index.html" else requestedPath
            val normalized = Paths.get(relativePath).normalize()
            if (normalized.startsWith("..")) {
                exchange.sendResponseHeaders(403, -1)
                return
            }
            val target = root.resolve(normalized)
            if (!Files.exists(target) || Files.isDirectory(target)) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val bytes = Files.readAllBytes(target)
            val contentType = when (target.toString().substringAfterLast('.', "")) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
            exchange.responseHeaders.add("Content-Type", "$contentType; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
