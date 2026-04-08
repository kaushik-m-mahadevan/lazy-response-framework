package com.lazyresponse.graph;

import com.lazyresponse.registry.DownstreamRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a self-contained HTML page at {@code /lazy/graph} that renders the downstream
 * dependency graph as a Mermaid.js flowchart.
 *
 * <p>Registered automatically by the framework's auto-configuration. No additional setup
 * is required. The page loads the Mermaid renderer from CDN and requires no frontend tooling.
 *
 * <p>The graph definition is generated at request time from the sealed
 * {@link DownstreamRegistry}, so it always reflects the current registered topology.
 */
@RestController
public class LazyGraphController {

    private final DownstreamRegistry registry;

    public LazyGraphController(DownstreamRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/lazy/graph", produces = MediaType.TEXT_HTML_VALUE)
    public String graph() {
        String mermaidDefinition = registry.getGraph().toMermaidFlowchart();
        return buildHtmlPage(mermaidDefinition);
    }

    private String buildHtmlPage(String mermaidDefinition) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Lazy Response — Downstream Graph</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                            background: #f8f9fa;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            padding: 2rem;
                            margin: 0;
                        }
                        h1 { color: #212529; margin-bottom: 0.25rem; }
                        p  { color: #6c757d; font-size: 0.9rem; margin-bottom: 2rem; }
                        .graph-container {
                            background: #fff;
                            border: 1px solid #dee2e6;
                            border-radius: 8px;
                            padding: 2rem;
                            min-width: 400px;
                        }
                    </style>
                </head>
                <body>
                    <h1>Downstream Dependency Graph</h1>
                    <p>Auto-generated from the registered @Downstream topology at startup.</p>
                    <div class="graph-container">
                        <pre class="mermaid">
                """ + mermaidDefinition + """
                        </pre>
                    </div>
                    <script type="module">
                        import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';
                        mermaid.initialize({ startOnLoad: true, theme: 'default' });
                    </script>
                </body>
                </html>
                """;
    }
}
