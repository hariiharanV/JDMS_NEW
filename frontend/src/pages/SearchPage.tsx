import { useState } from 'react';
import { CloseOutlined, EyeOutlined, FileTextOutlined, SearchOutlined, TableOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Button, Card, Checkbox, DatePicker, Input, Pagination, Segmented, Skeleton, Table } from 'antd';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useCabinets, useDocumentTypes, useTypeMinimum } from '../api/queries';
import type { DocumentDetail, DocumentStatus, SearchHit, SearchResult } from '../api/types';
import { ClassificationLabel } from '../components/ClassificationLabel';
import { DocumentPreview } from '../components/DocumentPreview';
import { DocumentTitle } from '../components/DocumentTitle';
import { EmptyState } from '../components/EmptyState';
import { ErrorAlert } from '../components/ErrorAlert';
import { PageHeader } from '../components/PageHeader';
import { Snippet } from '../components/Snippet';
import { StatusChip } from '../components/StatusChip';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';

/** Facets as the API names them, and the URL parameter of each. */
const FACETS = [
  { facet: 'status', param: 'status' },
  { facet: 'documentType', param: 'type' },
  { facet: 'cabinet', param: 'cabinet' },
  { facet: 'branch', param: 'branch' },
] as const;
type Facet = (typeof FACETS)[number]['facet'];
const PAGE_SIZE = 20;
/** OpenSearch pages no further than this. */
const MAX_RESULTS = 10_000;

/**
 * Full-text and metadata search (spec §4.6, UI handoff §6.5). The URL holds the whole view, so a search can be
 * bookmarked and shared; results are already trimmed to what the caller may see.
 */
export function SearchPage() {
  const { t } = useTranslation();
  const format = useFormatters();
  const [params, setParams] = useSearchParams();
  const [open, setOpen] = useState<string>();
  const cabinets = useCabinets();
  const types = useDocumentTypes();
  const label = useLocalizedLabel();

  const q = (params.get('q') ?? '').trim();
  const scope = params.get('scope') === 'cif' ? 'cif' : 'all';
  const view = params.get('view') === 'table' ? 'table' : 'list';
  const page = Math.max(0, Number(params.get('page') ?? '1') - 1) || 0;
  const value = (param: string) => params.get(param) ?? undefined;
  const from = value('from');
  const to = value('to');
  const filtered = FACETS.some(({ param }) => params.has(param)) || !!from || !!to;
  const active = !!q || filtered;

  /** Changes some parameters; any change but paging goes back to the first page. */
  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, change]) => (change ? next.set(key, change) : next.delete(key)));
    if (!('page' in changes)) next.delete('page');
    setOpen(undefined);
    setParams(next);
  };

  const search = useQuery({
    queryKey: ['search', params.toString()],
    queryFn: () => api<SearchResult>('/search', {
      method: 'POST',
      body: {
        query: scope === 'cif' || !q ? undefined : q,
        filters: {
          cif: scope === 'cif' && q ? q : undefined,
          status: value('status'), documentType: value('type'), cabinet: value('cabinet'), branch: value('branch'),
          createdFrom: from, createdTo: to,
        },
        facets: FACETS.map(({ facet }) => facet),
        page,
        size: PAGE_SIZE,
      },
    }),
    enabled: active,
    // keep the results and facets on screen while the next search runs
    placeholderData: keepPreviousData,
  });
  const total = search.data?.total ?? 0;

  const facetLabel = (facet: Facet, bucket: string): string => {
    switch (facet) {
      case 'status': return t(`status.${bucket}`, { defaultValue: bucket });
      case 'documentType': {
        const type = types.data?.find((candidate) => candidate.code === bucket);
        return type ? label(type) : bucket;
      }
      case 'cabinet': return cabinets.data?.find((cabinet) => cabinet.code === bucket)?.name ?? bucket;
      default: return bucket;
    }
  };
  const pills = [
    ...FACETS.filter(({ param }) => params.has(param)).map(({ facet, param }) => ({
      key: param,
      text: `${t(`search.facet.${facet}`)}: ${facetLabel(facet, params.get(param) ?? '')}`,
      remove: { [param]: undefined },
    })),
    ...(from || to ? [{
      key: 'created',
      text: `${t('search.created')}: ${[from && format.date(from), to && format.date(to)].filter(Boolean).join(' – ')}`,
      remove: { from: undefined, to: undefined },
    }] : []),
  ];
  const clearAll = Object.fromEntries([...FACETS.map(({ param }) => param), 'from', 'to'].map((key) => [key, undefined]));

  const title = !active ? t('search.title')
    : q ? t(scope === 'cif' ? 'search.resultsForCif' : 'search.resultsFor', { count: total, total: format.number(total), q })
      : t('search.results', { count: total, total: format.number(total) });

  return (
    <section className="page">
      <PageHeader
        title={search.isLoading ? t('search.title') : title}
        description={t('search.scopeNote')}
        actions={active ? (
          <Segmented
            aria-label={t('search.view')}
            value={view}
            onChange={(next) => update({ view: next === 'table' ? 'table' : undefined, page: value('page') })}
            options={[
              { value: 'list', label: t('search.viewList'), icon: <UnorderedListOutlined aria-hidden  /> },
              { value: 'table', label: t('search.viewTable'), icon: <TableOutlined aria-hidden  /> },
            ]}
          />
        ) : undefined}
      />
      <div className="jdms-search-bar">
        <Input.Search
          className="jdms-search-input"
          aria-label={t('search.query')}
          placeholder={scope === 'cif' ? t('search.cifPlaceholder') : t('search.placeholder')}
          enterButton={t('search.submit')}
          key={`${scope}-${q}`}
          defaultValue={q}
          allowClear
          onSearch={(text) => update({ q: text.trim() || undefined })}
        />
        <Segmented
          aria-label={t('search.scope')}
          value={scope}
          onChange={(next) => update({ scope: next === 'cif' ? 'cif' : undefined })}
          options={[{ value: 'all', label: t('search.everything') }, { value: 'cif', label: t('document.cif') }]}
        />
      </div>
      {/* TODO(backend-gap: G18): sorting (best match, newest first, title) is fixed by the API: best match, then newest */}
      <ErrorAlert error={search.error} />
      {!active ? (
        <Card>
          <EmptyState icon={<SearchOutlined aria-hidden  />} title={t('search.startTitle')} description={t('search.start')} />
        </Card>
      ) : (
        <div className="jdms-search">
          <Card className="jdms-refine" title={<h2 className="jdms-card-title">{t('search.facets')}</h2>}
            extra={pills.length > 0 ? <Button type="link" onClick={() => update(clearAll)}>{t('search.clearAll')}</Button> : null}>
            {FACETS.map(({ facet, param }) => {
              const buckets = search.data?.facets[facet] ?? [];
              if (buckets.length === 0) return null;
              return (
                // TODO(backend-gap: G17): one value per facet; the API cannot combine values of a group with OR
                <div key={facet} role="group" aria-labelledby={`facet-${facet}`} className="jdms-facet">
                  <span id={`facet-${facet}`} className="jdms-facet-title">{t(`search.facet.${facet}`)}</span>
                  {buckets.map((bucket) => (
                    <Checkbox key={bucket.value} checked={params.get(param) === bucket.value}
                      onChange={(event) => update({ [param]: event.target.checked ? bucket.value : undefined })}>
                      <span className="jdms-facet-value">
                        <span>{facetLabel(facet, bucket.value)}</span>
                        <span className="jdms-facet-count">{format.number(bucket.count)}</span>
                      </span>
                    </Checkbox>
                  ))}
                </div>
              );
            })}
            <div role="group" aria-labelledby="facet-created" className="jdms-facet">
              <span id="facet-created" className="jdms-facet-title">{t('search.created')}</span>
              <DatePicker.RangePicker
                className="full-width"
                format="D MMM YYYY"
                allowEmpty={[true, true]}
                aria-label={t('search.created')}
                value={[from ? dayjs(from) : null, to ? dayjs(to) : null]}
                onChange={(range) => update({
                  from: range?.[0]?.format('YYYY-MM-DD'),
                  to: range?.[1]?.format('YYYY-MM-DD'),
                })}
              />
            </div>
          </Card>

          <div className="jdms-results">
            <div className="jdms-results-bar">
              {pills.length > 0 ? (
                <div className="jdms-pills">
                  <span className="jdms-muted">{t('search.filteredBy')}</span>
                  {pills.map((pill) => (
                    <span key={pill.key} className="jdms-pill">
                      {pill.text}
                      <button type="button" aria-label={t('search.removeFilter', { name: pill.text })} onClick={() => update(pill.remove)}>
                        <CloseOutlined aria-hidden  />
                      </button>
                    </span>
                  ))}
                </div>
              ) : <span />}
              <span className="jdms-muted" aria-live="polite">
                {search.data && total > 0 ? t('common.range', {
                  from: format.number(page * PAGE_SIZE + 1),
                  to: format.number(Math.min(total, (page + 1) * PAGE_SIZE)),
                  total: format.number(total),
                }) : null}
              </span>
            </div>
            {search.isLoading ? <Card><Skeleton active /></Card> : total === 0 ? (
              <Card>
                <EmptyState icon={<SearchOutlined aria-hidden  />} title={t('search.noResults')} description={t('search.noResultsText')}
                  action={scope === 'all' && q && !/\s/.test(q) ? (
                    <Button onClick={() => update({ scope: 'cif' })}>{t('search.asCif', { q })}</Button>
                  ) : undefined} />
              </Card>
            ) : view === 'table' ? (
              <Card className="jdms-table-card">
                <Table<SearchHit>
                  rowKey="id"
                  dataSource={search.data?.items ?? []}
                  pagination={false}
                  scroll={{ x: 'max-content' }}
                  columns={[
                    {
                      title: t('document.title'),
                      dataIndex: 'title',
                      render: (text: string, hit) => <DocumentTitle id={hit.id} title={text} typeCode={hit.documentTypeCode} />,
                    },
                    { title: t('document.cif'), dataIndex: 'cif', render: (cif: string | null) => (cif ? <bdi>{cif}</bdi> : null) },
                    { title: t('document.status'), dataIndex: 'status', render: (status: DocumentStatus) => <StatusChip status={status} /> },
                    { title: t('document.branch'), dataIndex: 'branchCode' },
                    {
                      title: t('document.createdAt'), dataIndex: 'createdAt', align: 'end', className: 'jdms-muted',
                      render: (created: string) => format.listDateTime(created),
                    },
                  ]}
                />
              </Card>
            ) : (
              <Card className="jdms-table-card">
                <ol className="jdms-hits">
                  {(search.data?.items ?? []).map((hit) => (
                    <Hit key={hit.id} hit={hit} open={open === hit.id}
                      onToggle={() => setOpen((current) => (current === hit.id ? undefined : hit.id))} />
                  ))}
                </ol>
              </Card>
            )}
            {total > PAGE_SIZE ? (
              <Pagination className="jdms-pagination" current={page + 1} pageSize={PAGE_SIZE} showSizeChanger={false}
                total={Math.min(total, MAX_RESULTS)} onChange={(next) => update({ page: String(next) })} />
            ) : null}
          </div>
        </div>
      )}
    </section>
  );
}

/** One result: title, status, classification above the type minimum, labelled facts, snippets and the preview. */
function Hit({ hit, open, onToggle }: { hit: SearchHit; open: boolean; onToggle: () => void }) {
  const { t } = useTranslation();
  const format = useFormatters();
  const label = useLocalizedLabel();
  const types = useDocumentTypes();
  const minimumOf = useTypeMinimum();
  const type = types.data?.find((candidate) => candidate.code === hit.documentTypeCode);
  const panelId = `preview-${hit.id}`;
  return (
    <li className="jdms-hit">
      <div className="jdms-hit-row">
        {/* TODO(backend-gap: G20): search hits carry no version, so there is no thumbnail to show */}
        <span className="jdms-hit-thumb" aria-hidden="true"><FileTextOutlined aria-hidden  /></span>
        <div className="jdms-hit-text">
          <div className="jdms-hit-title">
            <Link to={`/documents/${hit.id}`}><bdi>{hit.title}</bdi></Link>
            <StatusChip status={hit.status} />
            <ClassificationLabel classification={hit.classification} inList minimum={minimumOf(hit.documentTypeCode)} />
          </div>
          <dl className="jdms-hit-meta">
            <div><dt className="visually-hidden">{t('document.type')}</dt><dd>{type ? label(type) : hit.documentTypeCode}</dd></div>
            {hit.cif ? <div><dt>{t('document.cif')}</dt><dd><bdi>{hit.cif}</bdi></dd></div> : null}
            {hit.accountNo ? <div><dt>{t('search.account')}</dt><dd><bdi>{hit.accountNo}</bdi></dd></div> : null}
            <div><dt>{t('document.branch')}</dt><dd>{hit.branchCode}</dd></div>
            <div><dt>{t('document.createdAt')}</dt><dd>{format.date(hit.createdAt)}</dd></div>
          </dl>
          {(hit.snippets ?? []).map((segments, index) => <Snippet key={index} segments={segments} />)}
        </div>
        <Button icon={<EyeOutlined aria-hidden  />} aria-expanded={open} aria-controls={panelId} onClick={onToggle}>
          {open ? t('search.hidePreview') : t('search.preview')}
        </Button>
      </div>
      {open ? <div id={panelId} className="jdms-hit-preview"><HitPreview id={hit.id} /></div> : null}
    </li>
  );
}

function HitPreview({ id }: { id: string }) {
  const document = useQuery({ queryKey: ['document', id], queryFn: () => api<DocumentDetail>(`/documents/${id}`) });
  if (document.error) return <ErrorAlert error={document.error} />;
  if (!document.data) return <Skeleton.Image active />;
  return (
    <DocumentPreview documentId={id} version={document.data.currentVersion} classification={document.data.classification}
      canView={document.data.allowedActions.includes('VIEW_CONTENT')} />
  );
}
