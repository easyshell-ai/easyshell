import React, { useState, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { CheckOutlined, CopyOutlined } from '@ant-design/icons';
import './MarkdownContent.css';

interface MarkdownContentProps {
  content: string;
}

interface CodeBlockProps {
  children?: React.ReactNode;
  className?: string;
}

const CodeBlock = ({ children, className }: CodeBlockProps) => {
  const [copied, setCopied] = useState(false);
  const codeRef = useRef<HTMLElement>(null);
  
  const match = /language-(\w+)/.exec(className || '');
  const language = match ? match[1] : '';

  const handleCopy = () => {
    if (codeRef.current) {
      const text = codeRef.current.textContent || '';
      navigator.clipboard.writeText(text).catch(() => {});
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="code-block-wrapper">
      <div className="code-block-header">
        <span className="code-block-lang">{language}</span>
        <button className="code-block-copy" onClick={handleCopy} title="Copy code">
          {copied ? <CheckOutlined /> : <CopyOutlined />}
        </button>
      </div>
      <pre className="code-block-pre">
        <code ref={codeRef} className={className}>
          {children}
        </code>
      </pre>
    </div>
  );
};

const markdownComponents: Components = {
  pre: ({ children }) => <>{children}</>,
  code: ({ children, className }) => {
    const isBlock = /language-(\w+)/.exec(className || '') || className?.includes('hljs');
    if (isBlock) {
      return (
        <CodeBlock className={className}>
          {children}
        </CodeBlock>
      );
    }
    return (
      <code className="inline-code">
        {children}
      </code>
    );
  },
  table: ({ children }) => (
    <div className="markdown-table-wrapper">
      <table className="markdown-table">
        {children}
      </table>
    </div>
  ),
  th: ({ children }) => <th>{children}</th>,
  td: ({ children }) => <td>{children}</td>,
  h1: ({ children }) => <h2 style={{ margin: '12px 0 4px' }}>{children}</h2>,
  h2: ({ children }) => <h3 style={{ margin: '12px 0 4px' }}>{children}</h3>,
  h3: ({ children }) => <h4 style={{ margin: '12px 0 4px' }}>{children}</h4>,
  li: ({ children }) => <li style={{ marginLeft: 16 }}>{children}</li>,
  p: ({ children }) => <p style={{ margin: '4px 0' }}>{children}</p>,
};

const MarkdownContent: React.FC<MarkdownContentProps> = React.memo(({ content }) => (
  <div className="markdown-container" style={{ userSelect: 'text', cursor: 'text' }}>
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeHighlight]}
      components={markdownComponents}
    >
      {content}
    </ReactMarkdown>
  </div>
));

export default MarkdownContent;
