import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface MarkdownContentProps {
  content: string;
}

/**
 * Shared Markdown renderer using ReactMarkdown + remark-gfm.
 * Replaces all dangerouslySetInnerHTML + formatMarkdown() patterns.
 */
const MarkdownContent: React.FC<MarkdownContentProps> = ({ content }) => (
  <ReactMarkdown
    remarkPlugins={[remarkGfm]}
    components={{
      code: ({ children, className, ...props }) => {
        const isBlock = className?.startsWith('language-');
        if (isBlock) {
          return (
            <code
              className={className}
              style={{
                display: 'block',
                fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
                fontSize: 13,
                lineHeight: 1.5,
              }}
              {...props}
            >
              {children}
            </code>
          );
        }
        return (
          <code
            style={{
              background: 'rgba(0,0,0,0.06)',
              padding: '2px 6px',
              borderRadius: 3,
              fontSize: 13,
              fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
            }}
            {...props}
          >
            {children}
          </code>
        );
      },
      pre: ({ children }) => (
        <pre
          style={{
            background: '#1e1e1e',
            color: '#d4d4d4',
            padding: 12,
            borderRadius: 6,
            overflowX: 'auto',
            fontSize: 13,
            margin: '8px 0',
            lineHeight: 1.5,
          }}
        >
          {children}
        </pre>
      ),
      table: ({ children }) => (
        <div style={{ overflowX: 'auto', margin: '8px 0' }}>
          <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 13 }}>
            {children}
          </table>
        </div>
      ),
      th: ({ children }) => (
        <th
          style={{
            border: '1px solid #d9d9d9',
            padding: '8px 12px',
            background: '#fafafa',
            textAlign: 'left',
            fontWeight: 600,
          }}
        >
          {children}
        </th>
      ),
      td: ({ children }) => (
        <td style={{ border: '1px solid #d9d9d9', padding: '8px 12px' }}>{children}</td>
      ),
      h1: ({ children }) => <h2 style={{ margin: '12px 0 4px' }}>{children}</h2>,
      h2: ({ children }) => <h3 style={{ margin: '12px 0 4px' }}>{children}</h3>,
      h3: ({ children }) => <h4 style={{ margin: '12px 0 4px' }}>{children}</h4>,
      li: ({ children }) => <li style={{ marginLeft: 16 }}>{children}</li>,
      p: ({ children }) => <p style={{ margin: '4px 0' }}>{children}</p>,
    }}
  >
    {content}
  </ReactMarkdown>
);

export default MarkdownContent;
