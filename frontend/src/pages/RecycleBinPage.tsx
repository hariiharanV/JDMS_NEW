import { useState } from 'react';
import { DeleteOutlined, RollbackOutlined } from '@ant-design/icons';
import { App, Button, Card } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { usePermissions } from '../api/queries';
import type { DocumentSummary, Page } from '../api/types';
import { DocumentTable } from '../components/DocumentTable';
import { EmptyState } from '../components/EmptyState';
import { ErrorAlert } from '../components/ErrorAlert';
import { PageHeader } from '../components/PageHeader';
import { useFormatters } from '../i18n/labels';

/** Deleted documents awaiting restore or disposition (spec §4.7, UI handoff §6.12). Restoring is direct: it is reversible. */
export function RecycleBinPage() {
  const { t } = useTranslation();
  const format = useFormatters();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const [page, setPage] = useState(0);
  const deleted = useQuery({
    queryKey: ['documents', 'deleted', page],
    queryFn: () => api<Page<DocumentSummary>>(`/documents?status=DELETED&page=${page}&size=20`),
  });
  const restore = useMutation({
    mutationFn: (id: string) => api<unknown>(`/documents/${id}/restore`, { method: 'POST', idempotent: true }),
    onSuccess: () => {
      void message.success(t('recycleBin.restored'));
      void queryClient.invalidateQueries({ queryKey: ['documents'] });
    },
  });

  return (
    <section className="page">
      <PageHeader title={t('recycleBin.title')} description={t('recycleBin.description')} />
      <ErrorAlert error={deleted.error ?? restore.error} />
      <Card className="jdms-table-card">
        <DocumentTable
          documents={deleted.data?.items ?? []}
          loading={deleted.isLoading}
          total={deleted.data?.total}
          page={page}
          onPage={setPage}
          columns={['title', 'cif', 'branch']}
          emptyText={<EmptyState icon={<DeleteOutlined aria-hidden  />} title={t('recycleBin.emptyTitle')} description={t('recycleBin.empty')} />}
          extraColumns={[
            // the last change of a deleted document is its deletion
            {
              title: t('recycleBin.deletedAt'), dataIndex: 'updatedAt', align: 'end', className: 'jdms-muted',
              render: (value: string) => <time dateTime={value}>{format.listDateTime(value)}</time>,
            },
            // TODO(backend-gap: G10): who deleted it (the list carries no actor names)
            ...(can('RESTORE') ? [{
              title: <span className="visually-hidden">{t('common.actions')}</span>,
              key: 'restore',
              render: (_: unknown, document: DocumentSummary) => (
                <Button icon={<RollbackOutlined aria-hidden  />} loading={restore.isPending && restore.variables === document.id}
                  aria-label={t('recycleBin.restoreTitle', { title: document.title })} onClick={() => restore.mutate(document.id)}>
                  {t('recycleBin.restore')}
                </Button>
              ),
            }] : []),
          ]}
        />
      </Card>
    </section>
  );
}
