import { useState, type ReactNode } from 'react';
import { DeleteOutlined, DownloadOutlined, EllipsisOutlined, LinkOutlined, RollbackOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Dropdown, Form, Input, Skeleton, Space, Table, Tabs, Tag, Typography,
  Upload } from 'antd';
import type { UploadFile } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { api, apiBlob } from '../api/client';
import { useCabinets, useDocumentTypes, useMe } from '../api/queries';
import type { ApprovalRecord, AuditEvent, DocumentDetail, Version } from '../api/types';
import { ApprovalTimeline } from '../components/ApprovalTimeline';
import { ClassificationLabel } from '../components/ClassificationLabel';
import { Code } from '../components/Code';
import { DocumentPreview } from '../components/DocumentPreview';
import { useDocumentFacts } from '../components/documentFacts';
import { EmptyState } from '../components/EmptyState';
import { ErrorAlert } from '../components/ErrorAlert';
import { FactList } from '../components/FactList';
import { InlineConfirm } from '../components/InlineConfirm';
import { PageHeader } from '../components/PageHeader';
import { Chip, StatusChip } from '../components/StatusChip';
import { TableScroll } from '../components/TableScroll';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';
import { documentActions, type DocumentAction } from './documentActions';

type Panel = 'none' | 'newVersion' | 'checkIn' | 'delete';

/** Document page (spec §8, UI handoff §6.3): preview beside the facts, versions, approval history, audit. */
export function DocumentPage() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const format = useFormatters();
  const label = useLocalizedLabel();
  const facts = useDocumentFacts();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const me = useMe();
  const types = useDocumentTypes();
  const cabinets = useCabinets();
  const [panel, setPanel] = useState<Panel>('none');
  const [tab, setTab] = useState('overview');

  const document = useQuery({
    queryKey: ['document', id],
    queryFn: () => api<DocumentDetail>(`/documents/${id}`),
    // the worker is still making the preview: look again shortly
    refetchInterval: (query) => {
      const status = query.state.data?.currentVersion?.processingStatus;
      return status === 'PENDING' || status === 'PROCESSING' ? 5_000 : false;
    },
  });
  const versions = useQuery({ queryKey: ['document', id, 'versions'], queryFn: () => api<Version[]>(`/documents/${id}/versions`) });
  const history = useQuery({ queryKey: ['document', id, 'history'], queryFn: () => api<ApprovalRecord[]>(`/documents/${id}/history`) });
  const allowed = new Set(document.data?.allowedActions ?? []);
  const audit = useQuery({
    queryKey: ['document', id, 'audit'],
    queryFn: () => api<AuditEvent[]>(`/documents/${id}/audit`),
    enabled: allowed.has('VIEW_AUDIT'),
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['document', id] });
  const done = (text: string) => () => {
    void message.success(text);
    setPanel('none');
    void refresh();
  };

  const remove = useMutation({
    mutationFn: () => api<unknown>(`/documents/${id}`, { method: 'DELETE', idempotent: true }),
    onSuccess: done(t('document.deleteRequested')),
  });
  const restore = useMutation({
    mutationFn: () => api<unknown>(`/documents/${id}/restore`, { method: 'POST', idempotent: true }),
    onSuccess: done(t('recycleBin.restored')),
  });
  const checkOut = useMutation({
    mutationFn: () => api<unknown>(`/documents/${id}/checkout`, { method: 'POST', idempotent: true }),
    onSuccess: done(t('document.checkedOut')),
  });
  const cancelCheckOut = useMutation({
    mutationFn: () => api<unknown>(`/documents/${id}/cancel-checkout`, { method: 'POST', idempotent: true }),
    onSuccess: done(t('document.checkOutCancelled')),
  });

  const current = document.data?.currentVersion;

  const download = async (version: Version) => {
    try {
      const blob = await apiBlob(`/documents/${id}/versions/${version.id}/content`);
      const url = URL.createObjectURL(blob);
      const anchor = window.document.createElement('a');
      anchor.href = url;
      anchor.download = version.originalFilename;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      void message.error(t('errors.generic'));
    }
  };
  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      void message.success(t('document.linkCopied'));
    } catch {
      void message.error(t('errors.generic'));
    }
  };

  if (document.error) {
    return <section className="page"><ErrorAlert error={document.error} /></section>;
  }
  const data = document.data;
  if (!data) {
    return <section className="page"><Skeleton active paragraph={{ rows: 8 }} /></section>;
  }
  const type = types.data?.find((candidate) => candidate.code === data.documentTypeCode);
  const cabinet = cabinets.data?.find((candidate) => candidate.code === data.cabinetCode);
  const actions = documentActions(data, allowed, me.data?.userId);
  const checkedOutByMe = data.checkedOutBy !== null && data.checkedOutBy === me.data?.userId;

  const run: Record<DocumentAction, () => void> = {
    download: () => { if (current) void download(current); },
    newVersion: () => setPanel('newVersion'),
    checkOut: () => checkOut.mutate(),
    checkIn: () => setPanel('checkIn'),
    cancelCheckOut: () => cancelCheckOut.mutate(),
    restore: () => restore.mutate(),
    delete: () => setPanel('delete'),
    copyLink: () => void copyLink(),
  };
  const busy: Partial<Record<DocumentAction, boolean>> = {
    checkOut: checkOut.isPending, cancelCheckOut: cancelCheckOut.isPending, restore: restore.isPending,
  };
  const icons: Partial<Record<DocumentAction, ReactNode>> = {
    download: <DownloadOutlined aria-hidden  />, restore: <RollbackOutlined aria-hidden  />, copyLink: <LinkOutlined aria-hidden  />, delete: <DeleteOutlined aria-hidden  />,
  };
  const actionLabel = (action: DocumentAction) => t(`document.actions.${action}`);

  const preview = data.status === 'DISPOSED' ? (
    <div className="jdms-panel">
      <EmptyState title={t('document.disposedTitle')}
        description={t('document.disposedText', { date: format.date(data.updatedAt) })} />
    </div>
  ) : (
    <DocumentPreview documentId={id} version={current} classification={data.classification}
      canView={allowed.has('VIEW_CONTENT')} />
  );

  return (
    <section className="page">
      {data.onLegalHold ? (
        // TODO(backend-gap: G14): the hold's name is not in the document response
        <Alert type="warning" showIcon title={t('document.onHold')} className="jdms-page-alert" />
      ) : null}
      {data.checkedOutBy !== null && data.status !== 'DELETED' ? (
        // TODO(backend-gap: G15): who checked it out (when it is someone else) and since when
        <Alert type="info" showIcon className="jdms-page-alert"
          title={checkedOutByMe ? t('document.checkedOutByYou') : t('document.checkedOutByOther')}
          action={checkedOutByMe && panel !== 'checkIn' ? (
            <Button onClick={() => setPanel('checkIn')}>{actionLabel('checkIn')}</Button>
          ) : undefined} />
      ) : null}
      <PageHeader
        breadcrumb={[
          { key: 'cabinet', title: <Link to={`/browse/${data.cabinetCode}`}>{cabinet?.name ?? data.cabinetCode}</Link> },
          { key: 'type', title: type ? label(type) : data.documentTypeCode },
          ...(data.cif ? [{
            key: 'cif',
            title: <Link to={`/search?scope=cif&q=${encodeURIComponent(data.cif)}`}><bdi>{data.cif}</bdi></Link>,
          }] : []),
        ]}
        title={<bdi>{data.title}</bdi>}
        meta={(
          <>
            <StatusChip status={data.status} />
            <ClassificationLabel classification={data.classification} />
            {current ? <span>{t('document.versionLabel', { version: current.number })}</span> : null}
            <span>{t('document.updated', { time: format.dateTime(data.updatedAt) })}</span>
          </>
        )}
        actions={(
          <>
            {actions.primary ? (
              <Button type="primary" icon={icons[actions.primary]} loading={busy[actions.primary]}
                onClick={run[actions.primary]}>
                {actionLabel(actions.primary)}
              </Button>
            ) : null}
            {actions.secondary.map((action) => (
              <Button key={action} icon={icons[action]} loading={busy[action]} onClick={run[action]}>
                {actionLabel(action)}
              </Button>
            ))}
            <Dropdown trigger={['click']} menu={{
              items: actions.more.map((action) => ({
                key: action,
                icon: icons[action],
                danger: action === 'delete',
                label: action === 'delete' ? t('document.actions.deleteMenu') : actionLabel(action),
              })),
              onClick: ({ key }) => run[key as DocumentAction](),
            }}>
              <Button icon={<EllipsisOutlined aria-hidden  />} aria-label={t('document.moreActions')} />
            </Dropdown>
          </>
        )}
      />
      {panel === 'delete' ? (
        <InlineConfirm message={t('document.deleteConfirm')} confirmLabel={t('document.deleteDocument')}
          onConfirm={() => remove.mutate()} onCancel={() => setPanel('none')} loading={remove.isPending} />
      ) : null}
      <ErrorAlert error={remove.error ?? restore.error ?? checkOut.error ?? cancelCheckOut.error} />
      {panel === 'newVersion' || panel === 'checkIn' ? (
        <NewVersionPanel documentId={id} checkedOutByMe={checkedOutByMe}
          onClose={() => setPanel('none')} onDone={done(t('version.checkedIn'))} />
      ) : null}
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          {
            key: 'overview',
            label: t('document.overview'),
            children: (
              <div className="jdms-overview">
                <div className="jdms-overview-preview">{preview}</div>
                <div className="jdms-overview-facts">
                  <Card>
                    <FactList sections={facts(data, type)} />
                  </Card>
                  {(history.data ?? []).length > 0 ? (
                    <Card>
                      <section className="jdms-facts-section">
                        <h2 className="jdms-facts-title">{t('document.approval')}</h2>
                        <ApprovalTimeline records={history.data ?? []} />
                      </section>
                    </Card>
                  ) : null}
                </div>
              </div>
            ),
          },
          {
            key: 'versions',
            label: versions.data ? t('document.versionsCount', { count: versions.data.length }) : t('document.versions'),
            children: (
              <Card className="jdms-table-card">
                <TableScroll label={t('document.versions')}>
                  <Table<Version>
                    rowKey="id"
                    loading={versions.isLoading}
                    dataSource={versions.data ?? []}
                    pagination={false}
                    scroll={{ x: 'max-content' }}
                    columns={[
                      {
                        title: t('version.number'),
                        dataIndex: 'number',
                        render: (number: string, version) => (
                          <Space size={8}>
                            <span>{number}</span>
                            {version.id === current?.id ? <Tag>{t('version.current')}</Tag> : null}
                          </Space>
                        ),
                      },
                      // TODO(backend-gap: G10): uploader names; the short ID stands in
                      { title: t('version.uploadedBy'), dataIndex: 'createdBy', render: (value: string) => <Code value={value} short /> },
                      { title: t('version.time'), dataIndex: 'createdAt', render: (value: string) => format.dateTime(value) },
                      {
                        title: t('version.status'),
                        dataIndex: 'approvalStatus',
                        render: (value: Version['approvalStatus'], version) => (
                          version.processingStatus === 'QUARANTINED' || version.processingStatus === 'FAILED'
                            ? <Chip tone="danger" marker="square">{t(`processing.${version.processingStatus}`)}</Chip>
                            : t(`approval.${value}`)
                        ),
                      },
                      { title: t('version.note'), dataIndex: 'comment' },
                      ...(allowed.has('DOWNLOAD') ? [{
                        title: <span className="visually-hidden">{t('document.download')}</span>,
                        key: 'download',
                        render: (_: unknown, version: Version) => (
                          <Button size="small" icon={<DownloadOutlined aria-hidden  />} onClick={() => void download(version)}
                            aria-label={t('version.downloadVersion', { version: version.number })}>
                            {t('document.download')}
                          </Button>
                        ),
                      }] : []),
                    ]}
                  />
                </TableScroll>
              </Card>
            ),
          },
          {
            key: 'history',
            label: t('document.history'),
            children: (history.data ?? []).length > 0 ? (
              <Card><ApprovalTimeline records={history.data ?? []} /></Card>
            ) : (
              <EmptyState title={t('document.noHistory')} />
            ),
          },
          ...(allowed.has('VIEW_AUDIT') ? [{
            key: 'audit',
            label: t('document.audit'),
            children: (
              <Card className="jdms-table-card">
                <TableScroll label={t('document.audit')}>
                  <Table<AuditEvent>
                    rowKey="id"
                    loading={audit.isLoading}
                    dataSource={audit.data ?? []}
                    pagination={{ pageSize: 20, hideOnSinglePage: true }}
                    scroll={{ x: 'max-content' }}
                    columns={[
                      { title: t('audit.time'), dataIndex: 'occurredAt', render: (value: string) => format.dateTime(value) },
                      { title: t('audit.action'), dataIndex: 'action', render: (value: string) => <Code value={value} /> },
                      // TODO(backend-gap: G10)
                      { title: t('audit.actor'), dataIndex: 'actorId', render: (value: string) => <Code value={value} short /> },
                      {
                        title: t('audit.outcome'),
                        dataIndex: 'outcome',
                        render: (value: AuditEvent['outcome']) => (
                          <Chip tone={value === 'SUCCESS' ? 'success' : 'danger'} marker={value === 'SUCCESS' ? 'dot' : 'square'}>
                            {t(`audit.outcomes.${value}`)}
                          </Chip>
                        ),
                      },
                    ]}
                  />
                </TableScroll>
              </Card>
            ),
          }] : []),
        ]}
      />
    </section>
  );
}

/**
 * Checks the new file in as the next major version, in place on the page. Checks the document out first unless the
 * caller already has it checked out.
 */
function NewVersionPanel({ documentId, checkedOutByMe, onClose, onDone }: {
  documentId: string; checkedOutByMe: boolean; onClose: () => void; onDone: () => void;
}) {
  const { t } = useTranslation();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [comment, setComment] = useState('');
  const checkIn = useMutation({
    mutationFn: async () => {
      const file = files[0]?.originFileObj;
      if (!file) {
        throw new Error('no file');
      }
      if (!checkedOutByMe) {
        await api<unknown>(`/documents/${documentId}/checkout`, { method: 'POST', idempotent: true });
      }
      const body = new FormData();
      body.append('comment', comment);
      body.append('file', file, file.name);
      return api<unknown>(`/documents/${documentId}/checkin`, { method: 'POST', form: body, idempotent: true });
    },
    onSuccess: onDone,
  });
  return (
    <section aria-label={t('document.newVersion')} className="inline-panel">
      <Typography.Title level={2} className="jdms-panel-title">{t('document.newVersion')}</Typography.Title>
      <Form layout="vertical" onFinish={() => checkIn.mutate()}>
        <Form.Item label={t('version.comment')}>
          <Input.TextArea value={comment} maxLength={1000} onChange={(event) => setComment(event.target.value)} />
        </Form.Item>
        <Form.Item label={t('upload.file')}>
          <Upload.Dragger fileList={files} maxCount={1} beforeUpload={() => false}
            onChange={({ fileList }) => setFiles(fileList.slice(-1))}>
            <p>{t('upload.dropHint')}</p>
          </Upload.Dragger>
        </Form.Item>
        <ErrorAlert error={checkIn.error} />
        <Space wrap>
          <Button type="primary" htmlType="submit" loading={checkIn.isPending} disabled={files.length === 0}>
            {t('version.checkIn')}
          </Button>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
        </Space>
      </Form>
    </section>
  );
}
