import type { ReactNode } from 'react';
import { CheckSquareOutlined, FileOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Skeleton, Table } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { api } from '../api/client';
import { useDocumentTypes, useMe, usePermissions } from '../api/queries';
import { countTasks, taskGroup, useInbox } from '../api/tasks';
import type { DocumentStatus, DocumentSummary, Page, SearchResult, Task } from '../api/types';
import { EmptyState } from '../components/EmptyState';
import { LinkButton } from '../components/LinkButton';
import { PageHeader } from '../components/PageHeader';
import { StatCard } from '../components/StatCard';
import { StatusChip } from '../components/StatusChip';
import { useErrorMessage } from '../components/errorMessage';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';

const NEEDS_ACTION = 4;
const RECENT = 6;
const MAKER_STATUSES: DocumentStatus[] = ['PENDING_APPROVAL', 'ACTIVE', 'REJECTED'];

/** Most urgent first: overdue, due soon, returned to the maker, then the inbox order (oldest first). */
function urgency(task: Task): number {
  if (task.slaStatus === 'OVERDUE') return 0;
  if (task.slaStatus === 'DUE_SOON') return 1;
  return task.kind === 'AMEND' ? 2 : 3;
}

/**
 * Home (UI handoff §6.1): what needs the user now, the counts behind their work, and recent documents. Makers see
 * their returned items and document counts; checkers their queue; everyone else recent documents.
 */
export function HomePage() {
  const { t } = useTranslation();
  const format = useFormatters();
  const label = useLocalizedLabel();
  const me = useMe();
  const { can, isLoading: permissionsLoading } = usePermissions();
  const types = useDocumentTypes();
  const inbox = useInbox();
  const maker = can('UPLOAD');
  const checker = can('APPROVE') && !maker;
  // TODO(backend-gap: G11): the status facet of the search counts every document the user may see, not only theirs
  const statuses = useQuery({
    queryKey: ['search', 'status-counts'],
    queryFn: () => api<SearchResult>('/search', { method: 'POST', body: { facets: ['status'], size: 1 } }),
    enabled: maker,
  });
  const recent = useQuery({
    queryKey: ['documents', 'recent'],
    queryFn: () => api<Page<DocumentSummary>>(`/documents?size=${RECENT}`),
  });

  const counts = countTasks(inbox.tasks);
  const statusCount = (status: DocumentStatus) =>
    statuses.data ? (statuses.data.facets.status?.find((bucket) => bucket.value === status)?.count ?? 0) : undefined;
  const name = me.data?.displayName ?? me.data?.username ?? '';
  const showTasks = maker || checker || inbox.total > 0;
  const urgent = [...inbox.tasks].sort((a, b) => urgency(a) - urgency(b)).slice(0, NEEDS_ACTION);

  const summary = inbox.isSuccess ? [
    maker ? (counts.returned > 0 ? t('home.summary.returned', { count: counts.returned }) : t('home.summary.noneReturned'))
      : inbox.total > 0 ? t('home.summary.waiting', { count: inbox.total }) : null,
    showTasks ? (counts.overdue > 0 ? t('home.summary.overdue', { count: counts.overdue }) : t('home.summary.noneOverdue')) : null,
  ].filter(Boolean).join(' ') : undefined;

  const typeName = (code: string) => {
    const type = types.data?.find((candidate) => candidate.code === code);
    return type ? label(type) : code;
  };

  return (
    <section className="page jdms-home">
      <PageHeader
        // decision D1: "Welcome" stays until the product owner chooses
        title={t('home.greeting', { name })}
        description={summary || undefined}
        actions={maker ? (
          <LinkButton to="/upload" type="primary" size="large" icon={<PlusOutlined aria-hidden  />}>{t('home.upload')}</LinkButton>
        ) : undefined}
      />

      {maker ? (
        <div className="jdms-stats">
          <StatCard to="/tasks?filter=returned" tone="warning" label={t('home.tiles.returned')} format={format.number}
            value={inbox.isSuccess ? counts.returned : undefined} hint={t('home.tiles.returnedHint')} />
          {MAKER_STATUSES.map((status) => (
            <StatCard key={status} to={`/search?status=${status}`} label={t(`status.${status}`)} format={format.number}
              value={statusCount(status)} hint={t(`home.tiles.${status}`)} />
          ))}
        </div>
      ) : checker ? (
        // TODO(backend-gap: G16): "Returned by you" needs a count of the checker's own returns
        <div className="jdms-stats">
          <StatCard to="/tasks" label={t('home.tiles.waiting')} format={format.number}
            value={inbox.isSuccess ? inbox.total : undefined} hint={t('home.tiles.waitingHint')} />
          <StatCard to="/tasks?filter=dueSoon" tone={counts.dueSoon > 0 ? 'warning' : 'default'} label={t('home.tiles.dueSoon')}
            format={format.number} value={inbox.isSuccess ? counts.dueSoon : undefined} hint={t('home.tiles.dueSoonHint')} />
        </div>
      ) : null}
      {statuses.error ? <Retry error={statuses.error} onRetry={() => void statuses.refetch()} /> : null}

      <div className="jdms-home-columns">
        {showTasks || permissionsLoading ? (
          <HomeCard className="jdms-home-tasks" title={t('home.needsAction')}
            extra={inbox.total > 0 ? <Link to="/tasks">{t('home.allTasks', { count: inbox.total })}</Link> : null}>
            {inbox.isLoading ? <Skeleton active className="jdms-card-pad" /> : inbox.error ? (
              <Retry error={inbox.error} onRetry={() => void inbox.refetch()} />
            ) : urgent.length === 0 ? (
              <EmptyState icon={<CheckSquareOutlined aria-hidden  />} title={t('home.caughtUp')} description={t('home.caughtUpText')} />
            ) : (
              <ul className="jdms-action-list">
                {urgent.map((task) => (
                  <li key={task.id}>
                    <div className="jdms-action-text">
                      <span id={`task-${task.id}`} className="jdms-action-title"><bdi>{task.summary}</bdi></span>
                      <span className="jdms-action-meta">
                        {t(`tasks.groups.${taskGroup(task)}`)} · {format.listDateTime(task.createdAt)}
                      </span>
                    </div>
                    <LinkButton to={`/tasks/${task.id}`} aria-describedby={`task-${task.id}`}>
                      {t(`home.act.${task.kind}`)}
                    </LinkButton>
                  </li>
                ))}
              </ul>
            )}
          </HomeCard>
        ) : null}

        <HomeCard className="jdms-home-recent" title={t('home.recentDocuments')}
          extra={<Link to="/browse">{t('home.browseAll')}</Link>}>
          {recent.error ? <Retry error={recent.error} onRetry={() => void recent.refetch()} /> : (
            <Table<DocumentSummary>
              rowKey="id"
              loading={recent.isLoading}
              dataSource={recent.data?.items ?? []}
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{
                emptyText: (
                  <EmptyState icon={<FileOutlined aria-hidden  />} title={t('home.noDocumentsTitle')} description={t('home.noDocuments')}
                    action={maker ? <LinkButton to="/upload">{t('home.upload')}</LinkButton> : undefined} />
                ),
              }}
              columns={[
                {
                  title: t('document.title'),
                  dataIndex: 'title',
                  render: (title: string, document) => (
                    <span className="jdms-title-cell">
                      <Link to={`/documents/${document.id}`}><bdi>{title}</bdi></Link>
                      <span className="jdms-title-sub">{typeName(document.documentTypeCode)}</span>
                    </span>
                  ),
                },
                { title: t('document.cif'), dataIndex: 'cif', render: (cif: string | null) => (cif ? <bdi>{cif}</bdi> : null) },
                { title: t('document.status'), dataIndex: 'status', render: (status: DocumentStatus) => <StatusChip status={status} /> },
                {
                  title: t('document.updatedAt'),
                  dataIndex: 'updatedAt',
                  align: 'end',
                  className: 'jdms-muted',
                  render: (value: string) => <time dateTime={value}>{format.listDateTime(value)}</time>,
                },
              ]}
            />
          )}
        </HomeCard>
      </div>
    </section>
  );
}

function HomeCard({ title, extra, className, children }: {
  title: ReactNode; extra?: ReactNode; className: string; children: ReactNode;
}) {
  return (
    <Card className={`jdms-home-card ${className}`} title={<h2 className="jdms-card-title">{title}</h2>} extra={extra}>
      {children}
    </Card>
  );
}

/** A card's own failure, with a way to try again; the rest of Home keeps working. */
function Retry({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useTranslation();
  const message = useErrorMessage();
  return (
    <Alert type="error" showIcon className="jdms-card-alert" title={message(error)}
      action={<Button size="small" onClick={onRetry}>{t('common.retry')}</Button>} />
  );
}
