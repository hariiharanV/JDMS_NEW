import { useEffect, useState } from 'react';
import { SafetyCertificateOutlined, WarningOutlined } from '@ant-design/icons';
import { App, Button, Card, DatePicker, Input, Segmented, Select, Spin, Table } from 'antd';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { api, ApiError } from '../api/client';
import type { AuditEvent, AuditVerification, Page } from '../api/types';
import { auditActionName, AUDIT_ACTIONS } from '../components/auditActions';
import { Code } from '../components/Code';
import { ErrorAlert } from '../components/ErrorAlert';
import { PageHeader } from '../components/PageHeader';
import { Chip } from '../components/StatusChip';
import { TableScroll } from '../components/TableScroll';
import { useFormatters } from '../i18n/labels';

const PAGE_SIZE = 50;
const PERIODS = ['24h', '7d', '30d', 'all', 'custom'] as const;
type Period = (typeof PERIODS)[number];
const HOURS: Partial<Record<Period, number>> = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
const OUTCOMES = ['SUCCESS', 'DENIED', 'ERROR'] as const;

/** Text filters wait until typing pauses, so every keystroke does not run (and audit) a query. */
function useDebounced(value: string, delay = 300): string {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return settled;
}

/**
 * The audit trail (spec §4.11, UI handoff §6.10): the hash chain's state, filters that apply as they change and live
 * in the URL, and the events with their IDs. Every query is itself audited.
 */
export function AuditPage() {
  const { t } = useTranslation();
  const format = useFormatters();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [by, setBy] = useState(params.get('actorId') ?? '');
  const [on, setOn] = useState(params.get('resourceId') ?? '');
  const settledBy = useDebounced(by.trim());
  const settledOn = useDebounced(on.trim());
  const period = (PERIODS.find((candidate) => candidate === params.get('period')) ?? '24h');
  const page = Math.max(0, Number(params.get('page') ?? '1') - 1) || 0;

  /** Changes some filters; any change but paging goes back to the first page. */
  const update = (changes: Record<string, string | undefined>) => {
    setParams((current) => {
      const next = new URLSearchParams(current);
      Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)));
      if (!('page' in changes)) next.delete('page');
      return next;
    });
  };
  useEffect(() => {
    if (settledBy !== (params.get('actorId') ?? '') || settledOn !== (params.get('resourceId') ?? '')) {
      update({ actorId: settledBy || undefined, resourceId: settledOn || undefined });
    }
    // only the typed values drive this; the URL follows them
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settledBy, settledOn]);

  // the period is relative to now, worked out when the query runs
  const [now] = useState(() => Date.now());
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  for (const key of ['actorId', 'resourceId', 'action', 'outcome']) {
    const value = params.get(key);
    if (value) query.set(key, value);
  }
  if (HOURS[period]) {
    query.set('from', new Date(now - (HOURS[period] ?? 0) * 3_600_000).toISOString());
  } else if (period === 'custom') {
    const from = params.get('from');
    const to = params.get('to');
    if (from) query.set('from', dayjs(from).startOf('day').toISOString());
    if (to) query.set('to', dayjs(to).endOf('day').toISOString());
  }

  const events = useQuery({
    queryKey: ['audit', query.toString()],
    queryFn: () => api<Page<AuditEvent>>(`/audit?${query.toString()}`),
    placeholderData: keepPreviousData,
  });
  const verification = useQuery({
    queryKey: ['audit', 'verification'],
    queryFn: () => api<AuditVerification>('/audit/verification'),
    retry: false,
  });
  const verify = useMutation({
    mutationFn: () => api<AuditVerification>('/audit/verification', { method: 'POST', idempotent: true }),
    onSuccess: () => {
      void message.success(t('audit.verified'));
      void queryClient.invalidateQueries({ queryKey: ['audit'] });
    },
  });
  const notVerified = verification.error instanceof ApiError && verification.error.status === 404;
  const chain = verification.data;

  return (
    <section className="page">
      {/* TODO(backend-gap: G9): "Export results" waits for an export endpoint */}
      <PageHeader title={t('audit.title')} description={t('audit.description')} />

      <Card className={`jdms-chain jdms-chain-${verify.isPending ? 'busy' : chain?.status === 'BROKEN' ? 'broken' : chain ? 'intact' : 'unknown'}`}>
        <div className="jdms-chain-row" aria-live="polite">
          <span className="jdms-chain-icon" aria-hidden="true">
            {verify.isPending ? <Spin size="small" /> : chain?.status === 'BROKEN' ? <WarningOutlined aria-hidden  /> : <SafetyCertificateOutlined aria-hidden  />}
          </span>
          <div className="jdms-chain-text">
            {verify.isPending ? <strong>{t('audit.verifying')}</strong> : chain?.status === 'VALID' ? (
              <>
                <strong>{t('audit.intact')}</strong>
                <span>{t('audit.intactText', { count: chain.eventsChecked, total: format.number(chain.eventsChecked), at: format.dateTime(chain.finishedAt) })}</span>
              </>
            ) : chain?.status === 'BROKEN' ? (
              <>
                <strong>{t('audit.broken', { seq: chain.brokenSeq })}</strong>
                <span>{chain.failure}</span>
              </>
            ) : notVerified ? <strong>{t('audit.notVerified')}</strong> : null}
          </div>
          <Button onClick={() => verify.mutate()} loading={verify.isPending}>{t('audit.verifyNow')}</Button>
        </div>
        <ErrorAlert error={verify.error} />
      </Card>

      <Card className="jdms-table-card">
        <div className="jdms-audit-filters">
          {/* TODO(backend-gap: G25): one "who or what" search over names; the API filters by exact IDs */}
          <label className="jdms-filter">
            <span>{t('audit.by')}</span>
            <Input allowClear value={by} placeholder={t('audit.byPlaceholder')} onChange={(event) => setBy(event.target.value)} />
          </label>
          <label className="jdms-filter">
            <span>{t('audit.on')}</span>
            <Input allowClear value={on} placeholder={t('audit.onPlaceholder')} onChange={(event) => setOn(event.target.value)} />
          </label>
          <div className="jdms-filter">
            <span id="audit-action">{t('audit.action')}</span>
            <Select aria-labelledby="audit-action" allowClear showSearch={{ optionFilterProp: 'label' }}
              placeholder={t('audit.allActions')} value={params.get('action') ?? undefined}
              onChange={(value?: string) => update({ action: value })}
              options={AUDIT_ACTIONS.map((code) => ({ value: code, label: auditActionName(code, t) }))} />
          </div>
          <div className="jdms-filter">
            <span id="audit-period">{t('audit.period')}</span>
            <Select aria-labelledby="audit-period" value={period}
              onChange={(value: Period) => update({ period: value === '24h' ? undefined : value, from: undefined, to: undefined })}
              options={PERIODS.map((value) => ({ value, label: t(`audit.periods.${value}`) }))} />
          </div>
          {period === 'custom' ? (
            <div className="jdms-filter">
              <span id="audit-range">{t('audit.range')}</span>
              <DatePicker.RangePicker aria-labelledby="audit-range" format="D MMM YYYY" allowEmpty={[true, true]}
                value={[params.get('from') ? dayjs(params.get('from')) : null, params.get('to') ? dayjs(params.get('to')) : null]}
                onChange={(range) => update({ from: range?.[0]?.format('YYYY-MM-DD'), to: range?.[1]?.format('YYYY-MM-DD') })} />
            </div>
          ) : null}
          <Segmented
            className="jdms-filter-outcome"
            aria-label={t('audit.outcome')}
            value={params.get('outcome') ?? 'ALL'}
            onChange={(value) => update({ outcome: value === 'ALL' ? undefined : String(value) })}
            options={[{ value: 'ALL', label: t('audit.allOutcomes') },
              ...OUTCOMES.map((value) => ({ value, label: t(`audit.outcomes.${value}`) }))]}
          />
        </div>
        <ErrorAlert error={events.error} />
        <TableScroll label={t('audit.title')}>
          <Table<AuditEvent>
            rowKey="id"
            loading={events.isLoading}
            dataSource={events.data?.items ?? []}
            scroll={{ x: 'max-content' }}
            pagination={{
              current: page + 1, pageSize: PAGE_SIZE, total: events.data?.total, showSizeChanger: false,
              onChange: (next) => update({ page: String(next) }),
              showTotal: (count, [from, to]) => t('common.range', { from: format.number(from), to: format.number(to), total: format.number(count) }),
            }}
            expandable={{
              expandedRowRender: (event) => (
                <dl className="jdms-audit-detail">
                  <div><dt>{t('audit.actorId')}</dt><dd><Code value={event.actorId} copyable /></dd></div>
                  {event.resourceId ? <div><dt>{t('audit.resourceId')}</dt><dd><Code value={event.resourceId} copyable /></dd></div> : null}
                  {event.requestId ? <div><dt>{t('audit.requestId')}</dt><dd><Code value={event.requestId} copyable /></dd></div> : null}
                  {event.clientIp ? <div><dt>{t('audit.clientIp')}</dt><dd><Code value={event.clientIp} /></dd></div> : null}
                  <div><dt>{t('audit.chainLink')}</dt><dd>{event.seq > 1 ? t('audit.follows', { seq: event.seq - 1 }) : t('audit.first')}</dd></div>
                  {Object.keys(event.details).length > 0 ? (
                    <div className="jdms-audit-details"><dt>{t('audit.details')}</dt><dd><pre>{JSON.stringify(event.details, null, 2)}</pre></dd></div>
                  ) : null}
                </dl>
              ),
            }}
            columns={[
              { title: t('audit.seq'), dataIndex: 'seq', className: 'jdms-muted' },
              { title: t('audit.time'), dataIndex: 'occurredAt', render: (value: string) => <time dateTime={value}>{format.listDateTime(value)}</time> },
              {
                title: t('audit.event'),
                dataIndex: 'action',
                render: (code: string) => (
                  <span className="jdms-title-cell"><strong>{auditActionName(code, t)}</strong><Code value={code} /></span>
                ),
              },
              {
                // TODO(backend-gap: G10): actor display names
                title: t('audit.by'),
                dataIndex: 'actorId',
                render: (actor: string, event) => (
                  <span className="jdms-title-cell"><span>{t(`audit.actorTypes.${event.actorType}`, { defaultValue: event.actorType })}</span><Code value={actor} short /></span>
                ),
              },
              {
                title: t('audit.on'),
                dataIndex: 'resourceType',
                render: (type: string, event) => (
                  <span className="jdms-title-cell">
                    <span>{t(`audit.resources.${type}`, { defaultValue: auditActionName(type, t) })}</span>
                    {event.resourceId ? <Code value={event.resourceId} short /> : null}
                  </span>
                ),
              },
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
    </section>
  );
}
