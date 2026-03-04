import { useState, useRef, useEffect, useCallback } from 'react';
import { Input, Button, Checkbox, Space, theme } from 'antd';
import type { InputRef } from 'antd';
import {
  CloseOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { SearchAddon } from '@xterm/addon-search';

interface SearchBarProps {
  searchAddon: SearchAddon | null;
  visible: boolean;
  onClose: () => void;
}

const SearchBar: React.FC<SearchBarProps> = ({ searchAddon, visible, onClose }) => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const inputRef = useRef<InputRef>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [regex, setRegex] = useState(false);

  useEffect(() => {
    if (visible && inputRef.current) {
      inputRef.current?.focus();
    }
  }, [visible]);

  const searchOptions = { caseSensitive, regex };

  const findNext = useCallback(() => {
    if (searchAddon && searchTerm) {
      searchAddon.findNext(searchTerm, searchOptions);
    }
  }, [searchAddon, searchTerm, searchOptions]);

  const findPrevious = useCallback(() => {
    if (searchAddon && searchTerm) {
      searchAddon.findPrevious(searchTerm, searchOptions);
    }
  }, [searchAddon, searchTerm, searchOptions]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      onClose();
    } else if (e.key === 'Enter') {
      if (e.shiftKey) {
        findPrevious();
      } else {
        findNext();
      }
    }
  };

  if (!visible) return null;

  return (
    <div
      style={{
        position: 'absolute',
        top: 8,
        right: 16,
        zIndex: 10,
        background: token.colorBgElevated,
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: 8,
        padding: '8px 12px',
        boxShadow: token.boxShadowSecondary,
        display: 'flex',
        alignItems: 'center',
        gap: 8,
      }}
    >
      <Input
        ref={inputRef}
        size="small"
        placeholder={t('terminal.search.placeholder')}
        value={searchTerm}
        onChange={(e) => setSearchTerm(e.target.value)}
        onKeyDown={handleKeyDown}
        style={{ width: 180 }}
      />
      <Space size={4}>
        <Button
          type="text"
          size="small"
          icon={<ArrowUpOutlined />}
          onClick={findPrevious}
          disabled={!searchTerm}
        />
        <Button
          type="text"
          size="small"
          icon={<ArrowDownOutlined />}
          onClick={findNext}
          disabled={!searchTerm}
        />
      </Space>
      <Checkbox
        checked={caseSensitive}
        onChange={(e) => setCaseSensitive(e.target.checked)}
        style={{ fontSize: 12 }}
      >
        {t('terminal.search.caseSensitive')}
      </Checkbox>
      <Checkbox
        checked={regex}
        onChange={(e) => setRegex(e.target.checked)}
        style={{ fontSize: 12 }}
      >
        {t('terminal.search.regex')}
      </Checkbox>
      <Button
        type="text"
        size="small"
        icon={<CloseOutlined />}
        onClick={onClose}
      />
    </div>
  );
};

export default SearchBar;
