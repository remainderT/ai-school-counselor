import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function MarkdownMessage({ content }: { content: string }) {
  return (
    <div className="md-content">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ node: _node, ...props }) => (
            <a {...props} target="_blank" rel="noreferrer" />
          ),
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
