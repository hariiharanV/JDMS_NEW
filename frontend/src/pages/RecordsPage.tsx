import { useState, type ReactNode } from 'react';
import { DeleteOutlined, FieldTimeOutlined, PauseCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { App, Button, Card, Col, Form, Input, InputNumber, Modal, Radio, Row, Select, Space, Switch, Table, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { useDocumentTypes, usePermissions } from '../api/queries';
import type { DispositionBatch, LegalHold, RetentionPolicy } from '../api/types';
import { EmptyState } from '../components/EmptyState';
import { ErrorAlert } from '../components/ErrorAlert';
import { RowComment } from '../components/RowComment';
import { TableScroll } from '../components/TableScroll';
import { PageHeader } from '../components/PageHeader';
import { StatCard } from '../components/StatCard';
import { Chip } from '../components/StatusChip';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';

/**
 * Records management (spec §4.8, UI handoff §6.7): legal holds, retention policies and disposition on one page, each
 * a section the counts at the top jump to. Only releasing a hold and destroying a batch interrupt with a dialog:
 * neither can be undone.
 */
export function RecordsPage() {
  const { t } = useTranslation();
  const format = useFormatters();
  const { can } = usePermissions();
  const policies = useQuery({
    queryKey: ['retention-policies'],
    queryFn: () => api<RetentionPolicy[]>('/retention-policies'),
    enabled: can('MANAGE_RETENTION'),
  });
  const holds = useQuery({ queryKey: ['legal-holds'], queryFn: () => api<LegalHold[]>('/legal-holds'), enabled: can('MANAGE_HOLDS') });
  const batches = useQuery({
    queryKey: ['disposition-batches'],
    queryFn: () => api<DispositionBatch[]>('/disposition-batches'),
    enabled: can('DISPOSE'),
  });
  const activeHolds = holds.data?.filter((hold) => hold.status === 'ACTIVE');
  const pendingBatches = batches.data?.filter((batch) => batch.status === 'PENDING');

  return (
    <section className="page">
      <PageHeader title={t('records.title')} description={t('records.description')} />
      <div className="jdms-stats">
        {can('MANAGE_RETENTION') ? (
          <StatCard to="#retention" label={t('records.policies')} value={policies.data?.length} format={format.number}
            tone={policies.data?.length === 0 ? 'warning' : 'default'}
            hint={policies.data?.length === 0 ? t('records.noPoliciesHint') : t('records.policiesHint')} />
        ) : null}
        {can('MANAGE_HOLDS') ? (
          <StatCard to="#holds" label={t('records.activeHolds')} value={activeHolds?.length} format={format.number}
            hint={t('records.heldDocuments', {
              count: activeHolds?.reduce((sum, hold) => sum + hold.documentIds.length, 0) ?? 0,
              total: format.number(activeHolds?.reduce((sum, hold) => sum + hold.documentIds.length, 0) ?? 0),
            })} />
        ) : null}
        {can('DISPOSE') ? (
          <StatCard to="#disposition" label={t('records.batches')} value={pendingBatches?.length} format={format.number}
            tone={(pendingBatches?.length ?? 0) > 0 ? 'warning' : 'default'} hint={t('records.batchesHint')} />
        ) : null}
      </div>
      <div className="jdms-sections">
        {can('MANAGE_HOLDS') ? <Holds holds={holds.data} loading={holds.isLoading} error={holds.error} /> : null}
        {can('MANAGE_RETENTION') ? <Policies policies={policies.data} loading={policies.isLoading} error={policies.error} /> : null}
        {can('DISPOSE') ? <Disposition batches={batches.data} loading={batches.isLoading} error={batches.error} /> : null}
      </div>
    </section>
  );
}

function Section({ id, title, extra, children }: { id: string; title: string; extra?: ReactNode; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} tabIndex={-1} className="jdms-section">
      <Card title={<h2 id={`${id}-title`} className="jdms-card-title">{title}</h2>} extra={extra}>{children}</Card>
    </section>
  );
}

interface HoldForm { name: string; reason: string; cif?: string }

function Holds({ holds, loading, error }: { holds: LegalHold[] | undefined; loading: boolean; error: unknown }) {
  const { t } = useTranslation();
  const format = useFormatters();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [releasing, setReleasing] = useState<LegalHold | null>(null);
  const [form] = Form.useForm<HoldForm>();
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['legal-holds'] });
  const create = useMutation({
    mutationFn: async (values: HoldForm) => {
      const hold = await api<LegalHold>('/legal-holds', { method: 'POST', body: { name: values.name, reason: values.reason }, idempotent: true });
      if (values.cif?.trim()) {
        await api<LegalHold>(`/legal-holds/${hold.id}/items`, { method: 'POST', body: { cif: values.cif.trim() }, idempotent: true });
      }
    },
    onSuccess: () => { setOpen(false); form.resetFields(); void message.success(t('records.holdCreated')); void refresh(); },
  });
  const release = useMutation({
    // TODO(backend-gap: G23): the release takes no reason, so the dialog asks for none
    mutationFn: (id: string) => api<unknown>(`/legal-holds/${id}/release`, { method: 'POST', idempotent: true }),
    onSuccess: () => { setReleasing(null); void message.success(t('records.released')); void refresh(); },
  });

  return (
    <Section id="holds" title={t('records.holds')}
      extra={open ? null : <Button icon={<PlusOutlined aria-hidden  />} onClick={() => setOpen(true)}>{t('records.newHold')}</Button>}>
      {open ? (
        <section aria-label={t('records.newHold')} className="inline-panel">
          <h3 className="jdms-panel-title">{t('records.newHold')}</h3>
          <Form form={form} layout="vertical" onFinish={(values) => create.mutate(values)}>
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item name="name" label={t('records.name')} rules={[{ required: true, message: t('upload.required', { field: t('records.name') }) }]}>
                  <Input maxLength={200} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="cif" label={t('records.holdByCif')} extra={t('records.holdByCifHint')}><Input maxLength={50} /></Form.Item>
              </Col>
            </Row>
            <Form.Item name="reason" label={t('records.reason')} rules={[{ required: true, message: t('upload.required', { field: t('records.reason') }) }]}>
              <Input.TextArea maxLength={1000} autoSize={{ minRows: 2, maxRows: 5 }} />
            </Form.Item>
            <ErrorAlert error={create.error} />
            <Space wrap>
              <Button type="primary" htmlType="submit" loading={create.isPending}>{t('records.placeHold')}</Button>
              <Button onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
            </Space>
          </Form>
        </section>
      ) : null}
      <ErrorAlert error={error} />
      {loading ? null : (holds ?? []).length === 0 ? (
        <EmptyState icon={<PauseCircleOutlined aria-hidden  />} title={t('records.noHolds')} description={t('records.noHoldsText')} />
      ) : (
        <ul className="jdms-rows">
          {(holds ?? []).map((hold) => (
            <li key={hold.id} className="jdms-row-item">
              <PauseCircleOutlined className="jdms-row-icon" aria-hidden="true" />
              <div className="jdms-row-text">
                <span className="jdms-row-title">
                  <bdi>{hold.name}</bdi>
                  {hold.status === 'ACTIVE' ? <Chip tone="warning">{t('records.holding')}</Chip>
                    : <Chip tone="neutral" marker="bar">{t('records.holdStatus.RELEASED')}</Chip>}
                </span>
                <span>{hold.reason}</span>
                {/* TODO(backend-gap: G6): who placed or released the hold */}
                <span className="jdms-muted">
                  {t('records.holdMeta', { count: hold.documentIds.length, total: format.number(hold.documentIds.length), time: format.dateTime(hold.createdAt) })}
                </span>
              </div>
              {/* TODO(backend-gap: G7): "View documents" needs a search filter by legal hold */}
              {hold.status === 'ACTIVE' ? (
                <Button onClick={() => setReleasing(hold)}>{t('records.releaseMenu')}</Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      <Modal open={releasing !== null} title={t('records.releaseTitle')} okText={t('records.release')}
        okButtonProps={{ danger: true }} cancelText={t('common.cancel')} confirmLoading={release.isPending}
        onCancel={() => setReleasing(null)} onOk={() => releasing && release.mutate(releasing.id)}>
        <p>{t('records.releaseText', { count: releasing?.documentIds.length ?? 0, total: format.number(releasing?.documentIds.length ?? 0) })}</p>
        <ErrorAlert error={release.error} />
      </Modal>
    </Section>
  );
}

interface PolicyForm {
  code: string;
  name: string;
  triggerType: RetentionPolicy['triggerType'];
  eventType?: string;
  years?: number;
  months?: number;
  dispositionAction: RetentionPolicy['dispositionAction'];
  requiresApproval: boolean;
  documentTypeCodes: string[];
}

function Policies({ policies, loading, error }: { policies: RetentionPolicy[] | undefined; loading: boolean; error: unknown }) {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const types = useDocumentTypes();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<PolicyForm>();
  const trigger = Form.useWatch('triggerType', form);
  const propose = useMutation({
    mutationFn: (values: PolicyForm) => api<unknown>('/retention-policies', {
      method: 'POST',
      idempotent: true,
      body: {
        code: values.code, name: values.name, triggerType: values.triggerType,
        eventType: values.triggerType === 'EVENT' ? values.eventType : null,
        periodMonths: (values.years ?? 0) * 12 + (values.months ?? 0),
        dispositionAction: values.dispositionAction, requiresApproval: values.requiresApproval,
        documentTypeCodes: values.documentTypeCodes,
      },
    }),
    onSuccess: () => {
      setOpen(false);
      form.resetFields();
      void message.success(t('records.policyProposed'));
      void queryClient.invalidateQueries({ queryKey: ['retention-policies'] });
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
    },
  });
  const typeName = (code: string) => {
    const type = types.data?.find((candidate) => candidate.code === code);
    return type ? label(type) : code;
  };
  const required = (field: string) => [{ required: true, message: t('upload.required', { field }) }];

  return (
    <Section id="retention" title={t('records.policies')}
      extra={open ? null : <Button icon={<PlusOutlined aria-hidden  />} onClick={() => setOpen(true)}>{t('records.newPolicy')}</Button>}>
      {open ? (
        <section aria-label={t('records.newPolicy')} className="inline-panel">
          <h3 className="jdms-panel-title">{t('records.newPolicy')}</h3>
          <Form<PolicyForm> form={form} layout="vertical" onFinish={(values) => propose.mutate(values)}
            initialValues={{ triggerType: 'CREATION_DATE', dispositionAction: 'DESTROY', requiresApproval: true, years: 7, months: 0, documentTypeCodes: [] }}>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="code" label={t('records.code')} rules={[...required(t('records.code')), { pattern: /^[A-Z][A-Z0-9_]{1,49}$/, message: t('admin.codeFormat') }]}>
                  <Input maxLength={50} />
                </Form.Item>
              </Col>
              <Col xs={24} md={16}>
                <Form.Item name="name" label={t('records.name')} rules={required(t('records.name'))}><Input maxLength={200} /></Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="triggerType" label={t('records.startsFrom')}>
                  <Select aria-label={t('records.startsFrom')}
                    options={(['CREATION_DATE', 'EXPIRY_DATE', 'EVENT'] as const).map((value) => ({ value, label: t(`records.triggers.${value}`) }))} />
                </Form.Item>
              </Col>
              {trigger === 'EVENT' ? (
                <Col xs={24} md={8}>
                  <Form.Item name="eventType" label={t('records.eventType')} rules={required(t('records.eventType'))}
                    extra={t('records.eventTypeHint')}>
                    <Input maxLength={50} />
                  </Form.Item>
                </Col>
              ) : null}
              <Col xs={12} md={4}>
                <Form.Item name="years" label={t('records.years')}><InputNumber min={0} max={100} className="full-width" /></Form.Item>
              </Col>
              <Col xs={12} md={4}>
                <Form.Item name="months" label={t('records.monthsLabel')}><InputNumber min={0} max={11} className="full-width" /></Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="dispositionAction" label={t('records.atTheEnd')}>
                  <Radio.Group options={(['DESTROY', 'REVIEW'] as const).map((value) => ({ value, label: t(`records.actions.${value}`) }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="requiresApproval" label={t('records.requiresApproval')} valuePropName="checked"
                  extra={t('records.requiresApprovalHint')}>
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24}>
                <Form.Item name="documentTypeCodes" label={t('records.types')} rules={required(t('records.types'))}>
                  <Select mode="multiple" aria-label={t('records.types')}
                    options={(types.data ?? []).map((type) => ({ value: type.code, label: label(type) }))} />
                </Form.Item>
              </Col>
            </Row>
            <ErrorAlert error={propose.error} />
            <Space wrap>
              <Button type="primary" htmlType="submit" loading={propose.isPending}>{t('admin.propose')}</Button>
              <Button onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
            </Space>
          </Form>
        </section>
      ) : null}
      <ErrorAlert error={error} />
      <TableScroll label={t('records.policies')}>
        <Table<RetentionPolicy>
          rowKey="id"
          loading={loading}
          dataSource={policies ?? []}
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{
            emptyText: (
              <EmptyState icon={<FieldTimeOutlined aria-hidden  />} title={t('records.noPolicies')} description={t('records.noPoliciesText')}
                action={open ? undefined : <Button type="primary" onClick={() => setOpen(true)}>{t('records.firstPolicy')}</Button>} />
            ),
          }}
          columns={[
            {
              title: t('records.name'),
              dataIndex: 'name',
              render: (name: string, policy) => (
                <span className="jdms-title-cell"><strong>{name}</strong><span className="jdms-code">{policy.code}</span></span>
              ),
            },
            {
              title: t('records.startsFrom'),
              dataIndex: 'triggerType',
              render: (value: RetentionPolicy['triggerType'], policy) => (value === 'EVENT' && policy.eventType
                ? t('records.onEvent', { event: policy.eventType }) : t(`records.triggers.${value}`, { defaultValue: value })),
            },
            { title: t('records.period'), dataIndex: 'periodMonths', render: (value: number) => <Period months={value} /> },
            {
              title: t('records.atTheEnd'),
              dataIndex: 'dispositionAction',
              render: (value: RetentionPolicy['dispositionAction'], policy) =>
                (policy.requiresApproval ? t(`records.actionsApproved.${value}`) : t(`records.actions.${value}`)),
            },
            {
              title: t('records.types'),
              dataIndex: 'documentTypeCodes',
              render: (codes: string[]) => <span className="jdms-tags">{codes.map((code) => <Tag key={code}>{typeName(code)}</Tag>)}</span>,
            },
          ]}
        />
      </TableScroll>
    </Section>
  );
}

/** A period in years and months: "7 years", "1 year 6 months". */
function Period({ months }: { months: number }) {
  const { t } = useTranslation();
  const years = Math.floor(months / 12);
  const rest = months % 12;
  return (
    <>
      {[years > 0 ? t('records.yearCount', { count: years }) : null, rest > 0 || years === 0 ? t('records.monthCount', { count: rest }) : null]
        .filter(Boolean).join(' ')}
    </>
  );
}

const BATCH_TONE = { PENDING: ['warning', 'dot'], EXECUTED: ['neutral', 'bar'], REJECTED: ['danger', 'square'] } as const;

function Disposition({ batches, loading, error }: { batches: DispositionBatch[] | undefined; loading: boolean; error: unknown }) {
  const { t } = useTranslation();
  const format = useFormatters();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [destroying, setDestroying] = useState<DispositionBatch | null>(null);
  const [typed, setTyped] = useState('');
  const [rejecting, setRejecting] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['disposition-batches'] });
  const prepare = useMutation({
    mutationFn: () => api<DispositionBatch | undefined>('/disposition-batches/prepare', { method: 'POST', idempotent: true }),
    onSuccess: (batch) => { void message.info(batch ? t('records.prepared') : t('records.nothingDue')); void refresh(); },
  });
  const approve = useMutation({
    // TODO(backend-gap: G22): approving takes no comment, so the dialog asks only for the document count
    mutationFn: (id: string) => api<unknown>(`/disposition-batches/${id}/approve`, { method: 'POST', idempotent: true }),
    onSuccess: () => { setDestroying(null); setTyped(''); void message.success(t('records.destroyed')); void refresh(); },
  });
  const reject = useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) =>
      api<unknown>(`/disposition-batches/${id}/reject`, { method: 'POST', body: { comment: note }, idempotent: true }),
    onSuccess: () => { setRejecting(null); void message.success(t('records.batchRejected')); void refresh(); },
  });
  const count = destroying?.items.length ?? 0;

  return (
    <Section id="disposition" title={t('records.disposition')}
      extra={<Button onClick={() => prepare.mutate()} loading={prepare.isPending}>{t('records.prepare')}</Button>}>
      <ol className="jdms-steps">
        <li>{t('records.step1')}</li>
        <li>{t('records.step2')}</li>
        <li>{t('records.step3')}</li>
      </ol>
      <ErrorAlert error={error ?? prepare.error} />
      <TableScroll label={t('records.disposition')}>
        <Table<DispositionBatch>
          rowKey="id"
          loading={loading}
          dataSource={batches ?? []}
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: <EmptyState icon={<DeleteOutlined aria-hidden  />} title={t('records.noBatches')} description={t('records.noBatchesText')} /> }}
          expandable={{
            expandedRowKeys: rejecting ? [rejecting] : [],
            showExpandColumn: false,
            expandedRowRender: (batch) => (
              <RowComment id={`reject-${batch.id}`} label={t('records.rejectReason')} confirmLabel={t('records.reject')}
                loading={reject.isPending} error={reject.error} onCancel={() => setRejecting(null)}
                onConfirm={(note) => reject.mutate({ id: batch.id, note })} />
            ),
          }}
          columns={[
            { title: t('records.preparedAt'), dataIndex: 'preparedAt', render: (value: string) => format.dateTime(value) },
            {
              title: t('records.status'),
              dataIndex: 'status',
              render: (value: DispositionBatch['status']) => (
                <Chip tone={BATCH_TONE[value][0]} marker={BATCH_TONE[value][1]}>{t(`records.batchStatus.${value}`)}</Chip>
              ),
            },
            {
              title: t('records.documents'), dataIndex: 'items', align: 'end',
              render: (items: DispositionBatch['items']) => format.number(items.length),
            },
            {
              title: <span className="visually-hidden">{t('common.actions')}</span>,
              key: 'decide',
              // TODO(backend-gap: G24): who prepared the batch, to show "Waiting for a second records officer" to them
              render: (_: unknown, batch: DispositionBatch) => batch.status === 'PENDING' && rejecting !== batch.id ? (
                <Space wrap>
                  <Button danger onClick={() => { setDestroying(batch); setTyped(''); }}>{t('records.approveMenu')}</Button>
                  <Button onClick={() => setRejecting(batch.id)}>{t('records.reject')}</Button>
                </Space>
              ) : null,
            },
          ]}
        />
      </TableScroll>
      <Modal open={destroying !== null} title={t('records.destroyTitle', { count, total: format.number(count) })}
        okText={t('records.destroy')} okButtonProps={{ danger: true, disabled: typed.trim() !== String(count) }}
        cancelText={t('common.cancel')} confirmLoading={approve.isPending}
        onCancel={() => setDestroying(null)} onOk={() => destroying && approve.mutate(destroying.id)}>
        <p>{t('records.destroyText')}</p>
        <label className="jdms-confirm-label" htmlFor="destroy-count">{t('records.typeCount', { count })}</label>
        <Input id="destroy-count" inputMode="numeric" autoComplete="off" value={typed} onChange={(event) => setTyped(event.target.value)} />
        <ErrorAlert error={approve.error} />
      </Modal>
    </Section>
  );
}
