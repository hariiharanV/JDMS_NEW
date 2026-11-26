import { useState } from 'react';
import { ClockCircleOutlined } from '@ant-design/icons';
import { App, Button, Card, Space, Table } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useMe } from '../../api/queries';
import type { Page, PendingChange } from '../../api/types';
import { sentenceCase } from '../../components/auditActions';
import { Code } from '../../components/Code';
import { EmptyState } from '../../components/EmptyState';
import { ErrorAlert } from '../../components/ErrorAlert';
import { RowComment } from '../../components/RowComment';
import { TableScroll } from '../../components/TableScroll';
import { useFormatters } from '../../i18n/labels';

/**
 * Proposed administration changes waiting for a second administrator (UI handoff §6.8). Approve applies the change
 * (a new proposal can undo it); Reject asks for a reason in a comment field that opens on the row.
 */
export function PendingChanges() {
  const { t } = useTranslation();
  const format = useFormatters();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const me = useMe();
  const [rejecting, setRejecting] = useState<string | null>(null);
  const changes = useQuery({
    queryKey: ['pending-changes'],
    queryFn: () => api<Page<PendingChange>>('/admin/pending-changes?status=PENDING&size=100'),
  });
  const decide = useMutation({
    mutationFn: ({ id, decision, note }: { id: string; decision: 'approve' | 'reject'; note?: string }) =>
      api<unknown>(`/admin/pending-changes/${id}/${decision}`, { method: 'POST', body: { comment: note }, idempotent: true }),
    onSuccess: () => {
      void message.success(t('admin.decided'));
      setRejecting(null);
      for (const key of ['pending-changes', 'document-types', 'admin', 'retention-policies', 'tasks']) {
        void queryClient.invalidateQueries({ queryKey: [key] });
      }
    },
  });

  return (
    <Card className="jdms-table-card">
      <ErrorAlert error={changes.error ?? decide.error} />
      <TableScroll label={t('admin.pendingChanges')}>
        <Table<PendingChange>
          rowKey="id"
          loading={changes.isLoading}
          dataSource={changes.data?.items ?? []}
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: <EmptyState icon={<ClockCircleOutlined aria-hidden  />} title={t('admin.noPendingChanges')} description={t('admin.noPendingChangesText')} /> }}
          expandable={{
            expandedRowKeys: rejecting ? [rejecting] : [],
            showExpandColumn: false,
            expandedRowRender: (change) => (
              <RowComment id={`reject-${change.id}`} label={t('admin.rejectReason')} confirmLabel={t('tasks.decisions.REJECT')}
                loading={decide.isPending} onCancel={() => setRejecting(null)}
                onConfirm={(note) => decide.mutate({ id: change.id, decision: 'reject', note })} />
            ),
          }}
          columns={[
            { title: t('admin.summary'), dataIndex: 'summary', render: (summary: string) => <strong><bdi>{summary}</bdi></strong> },
            {
              title: t('admin.subject'),
              dataIndex: 'subjectType',
              render: (value: string) => t(`admin.subjects.${value}`, { defaultValue: sentenceCase(value) }),
            },
            {
              title: t('admin.operation'),
              dataIndex: 'operation',
              render: (value: string) => t(`admin.operations.${value}`, { defaultValue: sentenceCase(value) }),
            },
            {
              // TODO(backend-gap: G10): the proposer's name
              title: t('admin.proposedBy'),
              dataIndex: 'proposedAt',
              render: (value: string, change) => (
                <span className="jdms-title-cell">
                  <span>{format.dateTime(value)}</span>
                  {change.proposedBy === me.data?.userId ? <span className="jdms-title-sub">{t('admin.byYou')}</span> : <Code value={change.proposedBy} short />}
                </span>
              ),
            },
            {
              title: <span className="visually-hidden">{t('common.actions')}</span>,
              key: 'decide',
              render: (_: unknown, change: PendingChange) => change.canDecide ? (rejecting === change.id ? null : (
                <Space wrap>
                  <Button type="primary" loading={decide.isPending && decide.variables?.id === change.id && decide.variables.decision === 'approve'}
                    onClick={() => decide.mutate({ id: change.id, decision: 'approve' })}>
                    {t('tasks.decisions.APPROVE')}
                  </Button>
                  <Button danger onClick={() => setRejecting(change.id)}>{t('tasks.decisions.REJECT')}</Button>
                </Space>
              )) : change.proposedBy === me.data?.userId ? (
                <span className="jdms-muted">{t('admin.waitingForOther')}</span>
              ) : null,
            },
          ]}
        />
      </TableScroll>
    </Card>
  );
}
