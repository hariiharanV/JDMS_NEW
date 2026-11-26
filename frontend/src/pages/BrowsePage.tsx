import { useEffect, useMemo, useState } from 'react';
import { DownloadOutlined, FilterOutlined, FolderOpenOutlined, FolderOutlined, PlusOutlined } from '@ant-design/icons';
import { App, Button, Card, Input, Select, Tooltip, Tree } from 'antd';
import type { TreeDataNode } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { api, apiBlob } from '../api/client';
import { useCabinets, useDocumentTypes, useFolders, usePermissions } from '../api/queries';
import { type DocumentStatus, type DocumentSummary, type Folder, type Page } from '../api/types';
import { ClassificationLabel } from '../components/ClassificationLabel';
import { DocumentTable } from '../components/DocumentTable';
import { EmptyState } from '../components/EmptyState';
import { ErrorAlert } from '../components/ErrorAlert';
import { FactList } from '../components/FactList';
import { LinkButton } from '../components/LinkButton';
import { PageHeader } from '../components/PageHeader';
import { StatusChip } from '../components/StatusChip';
import { Thumbnail } from '../components/Thumbnail';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';

const ALL = 'all';
const STATUSES: DocumentStatus[] = ['ACTIVE', 'PENDING_APPROVAL', 'DRAFT', 'REJECTED'];
/** Below this width the properties pane is hidden and choosing a row opens the document (UI handoff §6.6). */
const PROPERTIES_QUERY = '(min-width: 1100px)';

function folderTree(folders: Folder[]): TreeDataNode[] {
  const children = new Map<string | null, Folder[]>();
  folders.forEach((folder) => children.set(folder.parentId, [...(children.get(folder.parentId) ?? []), folder]));
  const build = (parentId: string | null): TreeDataNode[] =>
    (children.get(parentId) ?? []).map((folder) => ({ key: folder.id, title: folder.name, children: build(folder.id) }));
  return build(null);
}

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const media = window.matchMedia(query);
    const onChange = () => setMatches(media.matches);
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [query]);
  return matches;
}

/**
 * Browse (spec §8, UI handoff §6.6) at /browse/:cabinet?/:folderId?: the cabinet's folders, the documents there,
 * and the selected document's properties.
 */
export function BrowsePage() {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const navigate = useNavigate();
  const { cabinet: cabinetCode, folderId = ALL } = useParams();
  const { can } = usePermissions();
  const cabinets = useCabinets();
  const types = useDocumentTypes();
  const wide = useMediaQuery(PROPERTIES_QUERY);
  const [type, setType] = useState<string>();
  const [status, setStatus] = useState<DocumentStatus>();
  const [cif, setCif] = useState<string>();
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<DocumentSummary>();
  const cabinet = cabinets.data?.find((candidate) => candidate.code === cabinetCode) ?? cabinets.data?.[0];
  const folders = useFolders(cabinet?.id);
  const folder = folders.data?.find((candidate) => candidate.id === folderId);

  const query = new URLSearchParams({ page: String(page), size: '20' });
  if (cabinet) query.set('cabinetId', cabinet.id);
  if (folderId !== ALL) query.set('folderId', folderId);
  if (type) query.set('type', type);
  if (status) query.set('status', status);
  if (cif) query.set('cif', cif);
  const documents = useQuery({
    queryKey: ['documents', 'browse', query.toString()],
    queryFn: () => api<Page<DocumentSummary>>(`/documents?${query.toString()}`),
    enabled: !!cabinet,
  });

  const tree = useMemo<TreeDataNode[]>(() => [
    { key: ALL, title: t('browse.allFolders'), children: folderTree(folders.data ?? []) },
  ], [folders.data, t]);
  const cabinetTypes = (types.data ?? []).filter((documentType) => documentType.cabinetId === cabinet?.id);
  const go = (code: string | undefined, folder: string) => {
    setPage(0);
    setSelected(undefined);
    void navigate(folder === ALL ? `/browse/${code ?? ''}` : `/browse/${code ?? ''}/${folder}`);
  };
  const open = (document: DocumentSummary) => void navigate(`/documents/${document.id}`);
  const filters = (change: () => void) => {
    change();
    setPage(0);
    setSelected(undefined);
  };

  return (
    <section className="page">
      <PageHeader
        breadcrumb={cabinet ? [
          { key: 'cabinet', title: <Link to={`/browse/${cabinet.code}`}>{cabinet.name}</Link> },
          { key: 'folder', title: folder?.name ?? t('browse.allFolders') },
        ] : undefined}
        title={cabinet?.name ?? t('browse.title')}
        actions={can('UPLOAD') && cabinet ? (
          <LinkButton type="primary" icon={<PlusOutlined aria-hidden  />}
            to={`/upload?cabinet=${cabinet.code}${folderId !== ALL ? `&folder=${folderId}` : ''}`}>
            {t('browse.uploadHere')}
          </LinkButton>
        ) : undefined}
      />
      <div className="jdms-browse">
        <Card className="jdms-browse-tree">
          <label className="jdms-pane-label" htmlFor="browse-cabinet">{t('browse.cabinet')}</label>
          <Select
            id="browse-cabinet"
            className="full-width"
            value={cabinet?.code}
            loading={cabinets.isLoading}
            options={(cabinets.data ?? []).map((candidate) => ({ value: candidate.code, label: candidate.name }))}
            onChange={(code: string) => { setType(undefined); go(code, ALL); }}
          />
          {/* TODO(backend-gap: G21): document counts per folder */}
          <Tree
            className="jdms-folder-tree"
            showIcon
            icon={({ expanded }: { expanded?: boolean }) => (expanded ? <FolderOpenOutlined aria-hidden  /> : <FolderOutlined aria-hidden  />)}
            treeData={tree}
            defaultExpandAll
            selectedKeys={[folderId]}
            onSelect={(keys) => go(cabinet?.code, keys.length > 0 ? String(keys[0]) : ALL)}
          />
        </Card>
        <Card className="jdms-browse-list jdms-table-card">
          <div className="jdms-toolbar">
            {/* TODO(backend-gap: G21): the list filters by CIF only, not by title, and cannot sort */}
            <Input
              allowClear
              prefix={<FilterOutlined aria-hidden  />}
              placeholder={t('browse.filterCif')}
              aria-label={t('browse.filterCif')}
              onPressEnter={(event) => filters(() => setCif(event.currentTarget.value.trim() || undefined))}
              onChange={(event) => { if (!event.target.value) filters(() => setCif(undefined)); }}
            />
            <Select allowClear placeholder={t('browse.allTypes')} aria-label={t('document.type')} value={type}
              options={cabinetTypes.map((documentType) => ({ value: documentType.code, label: label(documentType) }))}
              onChange={(next?: string) => filters(() => setType(next))} />
            <Select allowClear placeholder={t('browse.allStatuses')} aria-label={t('document.status')} value={status}
              options={STATUSES.map((value) => ({ value, label: t(`status.${value}`) }))}
              onChange={(next?: DocumentStatus) => filters(() => setStatus(next))} />
          </div>
          <ErrorAlert error={documents.error} />
          <DocumentTable
            documents={documents.data?.items ?? []}
            loading={documents.isLoading}
            total={documents.data?.total}
            page={page}
            onPage={setPage}
            onSelect={wide ? setSelected : open}
            onOpen={open}
            selectedId={selected?.id}
            minWidth={520}
            emptyText={<EmptyState icon={<FolderOutlined aria-hidden  />} title={t('browse.emptyTitle')} description={t('browse.emptyFolder')} />}
          />
        </Card>
        {wide ? (
          <Card className="jdms-browse-properties">
            {selected ? <Properties document={selected} canDownload={can('DOWNLOAD')} /> : (
              <EmptyState title={t('browse.selectDocument')} />
            )}
          </Card>
        ) : null}
      </div>
    </section>
  );
}

function Properties({ document, canDownload }: { document: DocumentSummary; canDownload: boolean }) {
  const { t } = useTranslation();
  const format = useFormatters();
  const label = useLocalizedLabel();
  const types = useDocumentTypes();
  const { message } = App.useApp();
  const type = types.data?.find((candidate) => candidate.code === document.documentTypeCode);
  const version = document.currentVersion;
  const download = async () => {
    if (!version) return;
    try {
      const blob = await apiBlob(`/documents/${document.id}/versions/${version.id}/content`);
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
  return (
    <section aria-label={t('browse.properties')} className="jdms-properties">
      <div className="jdms-properties-thumb"><Thumbnail document={document} /></div>
      <h2 className="jdms-properties-title"><bdi>{document.title}</bdi></h2>
      <div className="jdms-page-meta">
        <StatusChip status={document.status} />
        <ClassificationLabel classification={document.classification} />
      </div>
      <FactList sections={[{
        key: 'facts',
        facts: [
          { key: 'type', label: t('document.type'), value: type ? label(type) : document.documentTypeCode },
          { key: 'cif', label: t('document.cif'), value: <bdi>{document.cif ?? '–'}</bdi> },
          { key: 'branch', label: t('document.branch'), value: document.branchCode }, // TODO(backend-gap: G2)
          { key: 'version', label: t('version.number'), value: version?.number ?? '–' },
          { key: 'created', label: t('document.createdAt'), value: format.dateTime(document.createdAt) },
        ],
      }]} />
      <div className="jdms-properties-actions">
        <LinkButton type="primary" to={`/documents/${document.id}`} className="jdms-grow">{t('browse.open')}</LinkButton>
        {canDownload && version ? (
          <Tooltip title={t('document.download')}>
            <Button icon={<DownloadOutlined aria-hidden  />} aria-label={t('document.download')} onClick={() => void download()} />
          </Tooltip>
        ) : null}
      </div>
    </section>
  );
}
