import { useEffect, useRef } from 'react';
import { SearchOutlined } from '@ant-design/icons';
import { Input, type InputRef } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

function typingInField(target: EventTarget | null): boolean {
  return target instanceof HTMLElement
    && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
}

/** Header search: Enter opens Search with the query; the "/" key focuses it unless the user is typing elsewhere. */
export function GlobalSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const input = useRef<InputRef>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === '/' && !event.ctrlKey && !event.metaKey && !event.altKey && !typingInField(event.target)) {
        event.preventDefault();
        input.current?.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div role="search" className="jdms-global-search">
      <Input
        ref={input}
        type="search"
        aria-label={t('search.global')}
        placeholder={t('search.globalPlaceholder')}
        prefix={<SearchOutlined className="jdms-flip-rtl" aria-hidden="true" />}
        suffix={<kbd className="jdms-kbd" aria-hidden="true">/</kbd>}
        onPressEnter={(event) => {
          const query = event.currentTarget.value.trim();
          if (query) {
            void navigate(`/search?q=${encodeURIComponent(query)}`);
          }
        }}
      />
    </div>
  );
}
