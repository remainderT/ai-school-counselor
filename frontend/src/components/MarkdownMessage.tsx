import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface MarkdownMessageProps {
  content: string;
  onSourceCitationClick?: (index: number) => void;
}

export function MarkdownMessage({ content, onSourceCitationClick }: MarkdownMessageProps) {
  return (
    <div className="md-content">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ node: _node, ...props }) => (
            props.href?.startsWith("source-ref:") ? (
              <a
                {...props}
                href={props.href}
                className="md-citation-link"
                onClick={(event) => {
                  event.preventDefault();
                  const rawIndex = props.href?.replace("source-ref:", "") || "";
                  const sourceIndex = Number.parseInt(rawIndex, 10);
                  if (Number.isFinite(sourceIndex) && sourceIndex > 0) {
                    onSourceCitationClick?.(sourceIndex);
                  }
                }}
              />
            ) : (
              <a {...props} target="_blank" rel="noreferrer" />
            )
          ),
          p: ({ node: _node, ...props }) => <p {...props} />,
          ul: ({ node: _node, ...props }) => <ul className="md-list" {...props} />,
          ol: ({ node: _node, ...props }) => <ol className="md-list md-list-ordered" {...props} />,
          li: ({ node: _node, ...props }) => <li {...props} />,
          blockquote: ({ node: _node, ...props }) => <blockquote {...props} />,
          table: ({ node: _node, ...props }) => (
            <div className="md-table-wrap">
              <table {...props} />
            </div>
          ),
          thead: ({ node: _node, ...props }) => <thead {...props} />,
          tbody: ({ node: _node, ...props }) => <tbody {...props} />,
          tr: ({ node: _node, ...props }) => <tr {...props} />,
          th: ({ node: _node, ...props }) => <th {...props} />,
          td: ({ node: _node, ...props }) => <td {...props} />,
          hr: ({ node: _node, ...props }) => <hr className="md-divider" {...props} />,
          code: ({ className, children, ...props }) => {
            const language = (className || "").replace("language-", "");
            const text = String(children || "").replace(/\n$/, "");
            const isBlock = text.includes("\n") || language;
            if (!isBlock) {
              return (
                <code className="md-inline-code" {...props}>
                  {children}
                </code>
              );
            }
            return (
              <pre className="md-code">
                {language && <div className="md-code-lang">{language}</div>}
                <code className={className} {...props}>
                  {children}
                </code>
              </pre>
            );
          }
        }}
      >
        {content || ""}
      </ReactMarkdown>
    </div>
  );
}
