import DOMPurify from 'dompurify';
import {marked} from 'marked';
import {useCallback, useEffect, useMemo, useState} from 'react';

type InsightPayload = {
    event: string;
    requestId: string;
    body: any;
};

type InsightState = {
    status: string;
    rawJson?: any;
    content?: string;
};

type InsightConfig = {
    projectRoot?: string;
};

const App = () => {
    const [state, setState] = useState<InsightState>({status: '等待 Codex 分析...'});
    const [config, setConfig] = useState<InsightConfig>({});

    useEffect(() => {
        const listener = (event: Event) => {
            const custom = event as CustomEvent<InsightPayload>;
            const payload = custom.detail;
            if (!payload) return;
            if (payload.event === 'insight:init') {
                const body = payload.body ?? {};
                setConfig({projectRoot: typeof body.projectRoot === 'string' ? body.projectRoot : undefined});
                return;
            }
            if (payload.event === 'insight:progress') {
                setState((prev) => ({...prev, status: `${payload.body.message} ${payload.body.percentage}%`}));
            }
            if (payload.event === 'insight:update') {
                const body = payload.body ?? {};
                setState({
                    status: body.status === 'cached' ? '已加载 HTML 缓存' : '分析完成',
                    rawJson: body,
                    content: body.raw ?? ''
                });
            }
            if (payload.event === 'insight:error') {
                setState((prev) => ({
                    status: payload.body.message ?? '发生错误',
                    rawJson: prev.rawJson,
                    content: prev.content
                }));
            }
        };
        window.addEventListener('contextlens-insight', listener as EventListener);
        return () => window.removeEventListener('contextlens-insight', listener as EventListener);
    }, []);

    return (
        <div className="container">
            <section className="card">
                <h2>ContextLens</h2>
                <p>{state.status}</p>
            </section>
            {state.content && state.content.trim().length > 0 && (
                <section className="card">
                    <InsightDocument raw={state.content} projectRoot={config.projectRoot}/>
                </section>
            )}
        </div>
    );
};

export default App;

const InsightDocument = ({raw, projectRoot}: { raw?: string; projectRoot?: string }) => {
    const renderedHtml = useMemo(() => {
        const content = selectContent(raw);
        if (!content) return '';
        const enriched = enhanceFileReferences(content);
        return DOMPurify.sanitize(enriched, {ADD_ATTR: ['data-file-link', 'data-file', 'data-line']});
    }, [raw]);

    const handleClick = useCallback(
        (event: React.MouseEvent<HTMLDivElement>) => {
            const target = event.target as HTMLElement;
            const anchor = target.closest<HTMLElement>('[data-file-link="true"]');
            if (!anchor) return;
            event.preventDefault();
            const file = anchor.dataset.file ?? '';
            if (!file) return;
            const line = Number(anchor.dataset.line ?? '1') || 1;
            const resolved = resolveFilePath(file, projectRoot);
            if (!resolved || !window.intellijApi) return;
            window.intellijApi.postMessage(
                JSON.stringify({
                    event: 'insight:navigate',
                    requestId: 'insight-ui',
                    body: {file: resolved, line: Math.max(line - 1, 0)}
                })
            );
        },
        [projectRoot]
    );

    if (!renderedHtml) {
        return <p>暂无 HTML 内容</p>;
    }

    return <div className="insight-body" onClick={handleClick} dangerouslySetInnerHTML={{__html: renderedHtml}}/>;
};

const selectContent = (raw?: string): string => {
    if (!raw) {
        return '';
    }
    const trimmed = raw.trim();
    const looksLikeHtml = /^</.test(trimmed) && /<\/[^>]+>/.test(trimmed);
    if (looksLikeHtml) {
        return raw;
    }
    const html = marked.parse(raw);
    if (typeof html === 'string') {
        return html;
    }
    console.warn('[ContextLens] Marked returned async output, falling back to raw markdown'); // fallback guard
    return raw ?? '';
};

const enhanceFileReferences = (html: string): string => {
    if (!html || typeof document === 'undefined') return html;
    const container = document.createElement('div');
    container.innerHTML = html;
    const skipTags = new Set(['CODE', 'PRE', 'SCRIPT', 'STYLE']);

    const traverse = (node: Node) => {
        if (node.nodeType === Node.ELEMENT_NODE) {
            const element = node as HTMLElement;
            if (skipTags.has(element.tagName)) {
                return;
            }
            if (element.dataset?.fileLink === 'true') {
                return;
            }
        }
        if (node.nodeType === Node.TEXT_NODE) {
            const text = node.textContent ?? '';
            const matches = Array.from(text.matchAll(FILE_REFERENCE_PATTERN));
            if (matches.length === 0) {
                return;
            }
            const fragment = document.createDocumentFragment();
            let cursor = 0;
            matches.forEach((match) => {
                const index = match.index ?? 0;
                const [full, path, startLine, endLine] = match;
                if (index > cursor) {
                    fragment.append(text.slice(cursor, index));
                }
                fragment.append(createFileLink(path, startLine, endLine));
                cursor = index + full.length;
            });
            if (cursor < text.length) {
                fragment.append(text.slice(cursor));
            }
            node.parentNode?.replaceChild(fragment, node);
            return;
        }

        Array.from(node.childNodes).forEach(traverse);
    };

    Array.from(container.childNodes).forEach(traverse);
    return container.innerHTML;
};

const createFileLink = (path: string, startLine: string, endLine?: string): HTMLAnchorElement => {
    const anchor = document.createElement('a');
    const lineLabel = endLine ? `${startLine}-${endLine}` : startLine;
    anchor.textContent = `${extractFileName(path)}:${lineLabel}`;
    anchor.href = '#';
    anchor.dataset.fileLink = 'true';
    anchor.dataset.file = path;
    anchor.dataset.line = startLine;
    anchor.className = 'file-link';
    return anchor;
};

const extractFileName = (path: string): string => {
    const normalized = path.replace(/\\/g, '/');
    const parts = normalized.split('/');
    return parts[parts.length - 1] || path;
};

const resolveFilePath = (filePath: string, projectRoot?: string): string | null => {
    if (!filePath) return null;
    if (filePath.startsWith('/')) return filePath;
    if (!projectRoot || projectRoot.trim().length === 0) return null;
    const normalizedRoot = projectRoot.replace(/\\/g, '/').replace(/\/$/, '');
    const normalizedFile = filePath.replace(/\\/g, '/').replace(/^\//, '');
    return `${normalizedRoot}/${normalizedFile}`;
};

const FILE_REFERENCE_PATTERN = /([A-Za-z0-9_./-]+\.[A-Za-z0-9_]+):(\d+)(?:-(\d+))?/g;
