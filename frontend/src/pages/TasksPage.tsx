import { useRef, useState, type KeyboardEvent } from 'react';
import { CheckSquareOutlined, ExportOutlined, LeftOutlined } from '@ant-design/icons';
import { App, Button, Card, Grid, Segmented, Skeleton } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useDocumentTypes } from '../api/queries';
import { countTasks, matches, nextTask, taskGroup, TASK_FILTERS, useInbox, type TaskFilter } from '../api/tasks';
import type { ApprovalRecord, Decision, DocumentDetail, Task, Version } from '../api/types';
import { ApprovalTimeline } from '../components/ApprovalTimeline';
import { DecisionBar } from '../components/DecisionBar';
import { useDocumentFacts } from '../components/documentFacts';
import { DocumentPreview } from '../components/DocumentPreview';
import { EmptyState } from '../components/EmptyState';
import { ErrorAlert } from '../components/ErrorAlert';
import { FactList } from '../components/FactList';
import { PageHeader } from '../components/PageHeader';
import { SlaChip } from '../components/StatusChip';
import { useFormatters } from '../i18n/labels';

/**
 * The approval inbox (spec §4.4, UI handoff §6.2): the task list beside the selected task, which shows the document
 * preview and its fields next to the decision. Deciding opens the next task; nothing opens a dialog.
 */
export function TasksPage() {
  const { t } = useTranslation();
  const format = useFormatters();
  const { taskId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const narrow = !screens.lg;
  const inbox = useInbox();
  // Home's tiles open the inbox filtered (?filter=returned)
  const [filter, setFilter] = useState<TaskFilter>(() => {
    const requested = params.get('filter');
    return TASK_FILTERS.find((candidate) => candidate === requested) ?? 'all';
  });
  const list = useRef<HTMLUListElement>(null);

  // links from before the split view (/tasks?task=…)
  const legacy = params.get('task');
  if (legacy && !taskId) {
    return <Navigate to={`/tasks/${legacy}`} replace />;
  }

  const counts = countTasks(inbox.tasks);
  const visible = inbox.tasks.filter((task) => matches(task, filter));
  // on a wide screen the first task opens on its own; on a phone the list comes first
  const selectedId = taskId ?? (narrow ? undefined : visible[0]?.id);
  const filters = TASK_FILTERS.filter((candidate) => candidate === 'all' || counts[candidate] > 0 || candidate === filter);

  // arrow keys move through the list and open the task, like a mail inbox
  const onKey = (event: KeyboardEvent<HTMLAnchorElement>, index: number) => {
    const step = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    const target = visible[index + step];
    if (!step || !target) return;
    event.preventDefault();
    void navigate(`/tasks/${target.id}`);
    list.current?.querySelectorAll<HTMLAnchorElement>('a.jdms-task-link')[index + step]?.focus();
  };

  const header = (
    <PageHeader
      title={t('tasks.title')}
      actions={inbox.tasks.length > 0 ? (
        <Segmented<TaskFilter>
          className="jdms-pill-filter"
          aria-label={t('tasks.filterLabel')}
          value={filter}
          onChange={setFilter}
          options={filters.map((value) => ({
            value,
            label: t('tasks.filterCount', { filter: t(`tasks.filters.${value}`), count: format.number(counts[value]) }),
          }))}
        />
      ) : undefined}
    />
  );

  if (inbox.isLoading) {
    return <section className="page">{header}<Card><Skeleton active /></Card></section>;
  }
  if (inbox.tasks.length === 0) {
    return (
      <section className="page">
        {header}
        <ErrorAlert error={inbox.error} />
        {inbox.error ? null : (
          <Card>
            <EmptyState icon={<CheckSquareOutlined aria-hidden  />} title={t('tasks.emptyTitle')} description={t('tasks.empty')} />
          </Card>
        )}
      </section>
    );
  }

  const showList = !narrow || !taskId;
  const showDetail = !!selectedId && (!narrow || !!taskId);
  return (
    <section className="page">
      {header}
      <ErrorAlert error={inbox.error} />
      <div className="jdms-split">
        {showList ? (
          <section aria-label={t('tasks.list')} className="jdms-task-list-card">
            {visible.length === 0 ? (
              <EmptyState title={t('tasks.noMatch')} />
            ) : (
              <ul ref={list} className="jdms-task-list">
                {visible.map((task, index) => (
                  <li key={task.id}>
                    <Link to={`/tasks/${task.id}`} className="jdms-task-link" onKeyDown={(event) => onKey(event, index)}
                      aria-current={task.id === selectedId ? 'true' : undefined}>
                      <span className="jdms-task-caption">
                        <span>{t(`tasks.groups.${taskGroup(task)}`)}{task.round > 1 ? ` · ${t('tasks.round', { round: task.round })}` : ''}</span>
                        <SlaChip sla={task.slaStatus} />
                      </span>
                      <span className="jdms-task-title"><bdi>{task.summary}</bdi></span>
                      {/* TODO(backend-gap: G12): the maker's name ("From …") is not in the task list */}
                      <span className="jdms-task-meta">{t('tasks.receivedAt', { time: format.listDateTime(task.createdAt) })}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            {inbox.hasNextPage ? (
              <div className="jdms-task-more">
                <Button onClick={() => void inbox.fetchNextPage()} loading={inbox.isFetchingNextPage}>
                  {t('tasks.showMore', { count: inbox.total - inbox.tasks.length })}
                </Button>
              </div>
            ) : null}
          </section>
        ) : null}
        {showDetail && selectedId ? (
          <TaskDetail key={selectedId} taskId={selectedId} backLink={narrow}
            onDecided={() => {
              const next = nextTask(visible, selectedId);
              void navigate(next ? `/tasks/${next.id}` : '/tasks', { replace: !taskId });
            }} />
        ) : null}
      </div>
    </section>
  );
}

function TaskDetail({ taskId, backLink, onDecided }: { taskId: string; backLink: boolean; onDecided: () => void }) {
  const { t } = useTranslation();
  const format = useFormatters();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const types = useDocumentTypes();
  const facts = useDocumentFacts();
  const detail = useQuery({
    queryKey: ['task', taskId],
    queryFn: () => api<{ task: Task; history: ApprovalRecord[] }>(`/tasks/${taskId}`),
  });
  const task = detail.data?.task;
  const documentId = task?.documentId ?? null;
  const document = useQuery({
    queryKey: ['document', documentId],
    queryFn: () => api<DocumentDetail>(`/documents/${documentId}`),
    enabled: !!documentId,
  });
  // a new version under approval is not the current one yet: find it among the versions
  const otherVersion = !!task?.versionId && !!document.data && document.data.currentVersion?.id !== task.versionId;
  const versions = useQuery({
    queryKey: ['document', documentId, 'versions'],
    queryFn: () => api<Version[]>(`/documents/${documentId}/versions`),
    enabled: otherVersion,
  });
  const decide = useMutation({
    mutationFn: ({ decision, comment }: { decision: Decision; comment: string }) => api<unknown>(
      `/tasks/${taskId}/complete`, { method: 'POST', body: { decision, comment: comment || undefined }, idempotent: true }),
    onSuccess: () => {
      void message.success(t('tasks.decided'));
      void queryClient.invalidateQueries({ queryKey: ['tasks'] });
      if (documentId) void queryClient.invalidateQueries({ queryKey: ['document', documentId] });
      onDecided();
    },
  });

  const back = backLink ? (
    <Link to="/tasks" className="jdms-back-link"><LeftOutlined aria-hidden className="jdms-flip-rtl" /> {t('tasks.allTasks')}</Link>
  ) : null;
  if (detail.error) {
    return <section className="jdms-task-detail">{back}<ErrorAlert error={detail.error} /></section>;
  }
  if (!task) {
    return <section className="jdms-task-detail"><Skeleton active paragraph={{ rows: 6 }} /></section>;
  }
  const submitted = [...(detail.data?.history ?? [])].reverse().find((record) => record.decision === 'SUBMIT');
  const data = document.data;
  const type = types.data?.find((candidate) => candidate.code === data?.documentTypeCode);
  const version = otherVersion ? versions.data?.find((candidate) => candidate.id === task.versionId) : data?.currentVersion;
  const caption = [
    t(`tasks.groupCaption.${taskGroup(task)}`),
    task.stageName,
    t('tasks.round', { round: task.round }),
  ].filter(Boolean).join(' · ');

  return (
    <section aria-label={t('tasks.detail')} className="jdms-task-detail">
      <div className="jdms-task-head">
        <div>
          {back}
          <div className="jdms-task-caption-line">{caption}</div>
          <h2 className="jdms-task-heading"><bdi>{task.summary}</bdi></h2>
          <div className="jdms-task-submitted">
            {[
              submitted?.actorName ? t('tasks.submittedBy', { name: submitted.actorName }) : null,
              format.dateTime(submitted?.decidedAt ?? task.createdAt),
              task.dueAt ? t('tasks.dueAt', { time: format.dateTime(task.dueAt) }) : null,
            ].filter(Boolean).join(' · ')}
          </div>
        </div>
        {documentId ? (
          <Link to={`/documents/${documentId}`} className="jdms-task-open">
            {t('tasks.openDocument')} <ExportOutlined aria-hidden className="jdms-flip-rtl" />
          </Link>
        ) : null}
      </div>
      <div className="jdms-task-body">
        {documentId ? (
          <div className="jdms-task-preview">
            <ErrorAlert error={document.error} />
            {data ? (
              <DocumentPreview documentId={documentId} version={version} classification={data.classification}
                canView={data.allowedActions.includes('VIEW_CONTENT')} compact />
            ) : document.isLoading ? <Skeleton.Image active /> : null}
          </div>
        ) : null}
        <div className="jdms-task-facts">
          {data ? <FactList sections={facts(data, type)} headingLevel={3} /> : null}
          {/* TODO(backend-gap: G4): committee vote progress */}
          {(detail.data?.history ?? []).length > 0 ? (
            <section className="jdms-facts-section">
              <h3 className="jdms-facts-title">{t('tasks.history')}</h3>
              <ApprovalTimeline records={detail.data?.history ?? []} />
            </section>
          ) : null}
        </div>
      </div>
      <div className="jdms-task-footer">
        <ErrorAlert error={decide.error} />
        <DecisionBar decisions={task.decisions} pending={decide.isPending ? decide.variables?.decision ?? null : null}
          onDecide={(decision, comment) => decide.mutate({ decision, comment })} />
      </div>
    </section>
  );
}
