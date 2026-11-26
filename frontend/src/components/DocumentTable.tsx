import type { ReactNode } from 'react';
import { Table } from 'antd';
import type { TableProps } from 'antd';
import { useTranslation } from 'react-i18next';
import type { DocumentStatus, DocumentSummary } from '../api/types';
import { useFormatters } from '../i18n/labels';
import { DocumentTitle } from './DocumentTitle';
import { StatusChip } from './StatusChip';

export type DocumentColumn = 'title' | 'cif' | 'branch' | 'status' | 'updated';

interface Props {
  documents: DocumentSummary[];
  loading?: boolean;
  total?: number;
  page?: number;
  pageSize?: number;
  onPage?: (page: number) => void;
  /** A click selects (Browse shows its properties); a double click opens. */
  onSelect?: (document: DocumentSummary) => void;
  onOpen?: (document: DocumentSummary) => void;
  selectedId?: string;
  columns?: DocumentColumn[];
  extraColumns?: TableProps<DocumentSummary>['columns'];
  /** What to do when there is nothing to show: usually an EmptyState. */
  emptyText?: ReactNode;
  /** The width below which the table scrolls inside its card; titles wrap above it. */
  minWidth?: number;
}

/** Documents as a table (UI handoff §4.6): title with its type underneath, then the columns asked for. */
export function DocumentTable({ documents, loading, total, page, pageSize = 20, onPage, onSelect, onOpen, selectedId,
  columns = ['title', 'cif', 'status', 'updated'], extraColumns = [], emptyText, minWidth }: Props) {
  const { t } = useTranslation();
  const format = useFormatters();
  const all: Record<DocumentColumn, NonNullable<TableProps<DocumentSummary>['columns']>[number]> = {
    title: {
      key: 'title',
      title: t('document.title'),
      dataIndex: 'title',
      render: (title: string, document) => <DocumentTitle id={document.id} title={title} typeCode={document.documentTypeCode} />,
    },
    cif: { key: 'cif', title: t('document.cif'), dataIndex: 'cif', render: (cif: string | null) => (cif ? <bdi>{cif}</bdi> : null) },
    branch: { key: 'branch', title: t('document.branch'), dataIndex: 'branchCode' },
    status: { key: 'status', title: t('document.status'), dataIndex: 'status', render: (status: DocumentStatus) => <StatusChip status={status} /> },
    updated: {
      key: 'updated',
      title: t('document.updatedAt'),
      dataIndex: 'updatedAt',
      align: 'end',
      className: 'jdms-muted',
      render: (value: string) => <time dateTime={value}>{format.listDateTime(value)}</time>,
    },
  };
  return (
    <Table<DocumentSummary>
      rowKey="id"
      loading={loading}
      dataSource={documents}
      scroll={{ x: minWidth ?? 'max-content' }}
      locale={emptyText ? { emptyText } : undefined}
      rowClassName={(document) => (document.id === selectedId ? 'row-selected' : '')}
      onRow={(document) => ({
        onClick: () => onSelect?.(document),
        onDoubleClick: () => onOpen?.(document),
      })}
      pagination={onPage ? {
        current: (page ?? 0) + 1, pageSize, total, showSizeChanger: false, onChange: (next) => onPage(next - 1),
        showTotal: (count, [from, to]) => t('common.range', { from: format.number(from), to: format.number(to), total: format.number(count) }),
      } : false}
      columns={[...columns.map((column) => all[column]), ...extraColumns]}
    />
  );
}
