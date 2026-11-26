import { useMemo, useState } from 'react';
import { FolderOpenOutlined, FolderOutlined, PlusOutlined } from '@ant-design/icons';
import { App, Button, Card, Form, Input, Select, Space, Tree, TreeSelect } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, ifMatch } from '../../api/client';
import { useCabinets, useFolders } from '../../api/queries';
import type { Folder, PendingChange } from '../../api/types';
import { EmptyState } from '../../components/EmptyState';
import { ErrorAlert } from '../../components/ErrorAlert';
import { FactList } from '../../components/FactList';
import { folderTree } from './folderTree';

interface FolderVersion {
  id: string;
  parentId: string | null;
  name: string;
  version: number;
}

type Editing = { kind: 'create'; parentId: string | null } | { kind: 'edit' } | null;

/**
 * Folders (B-19): the virtual folder tree of each cabinet, where documents are filed and folder access rules apply.
 * Creating, renaming, moving and deleting (empty folders only) are proposals a second tenant administrator approves.
 */
export function Folders() {
  const { t } = useTranslation();
  const cabinets = useCabinets();
  const [cabinetId, setCabinetId] = useState<string>();
  const chosenCabinet = cabinetId ?? cabinets.data?.[0]?.id;
  const folders = useFolders(chosenCabinet);
  const [selectedId, setSelectedId] = useState<string>();
  const [editing, setEditing] = useState<Editing>(null);
  const all = useMemo(() => folders.data ?? [], [folders.data]);
  const selected = all.find((folder) => folder.id === selectedId);
  const tree = useMemo(() => folderTree(all), [all]);

  return (
    <div className="jdms-master-detail">
      <Card className="jdms-table-card jdms-master">
        <div className="jdms-toolbar">
          <Select aria-label={t('browse.cabinet')} value={chosenCabinet} loading={cabinets.isLoading}
            onChange={(value: string) => { setCabinetId(value); setSelectedId(undefined); setEditing(null); }}
            options={(cabinets.data ?? []).map((cabinet) => ({ value: cabinet.id, label: cabinet.name }))} />
          <Button icon={<PlusOutlined aria-hidden />} disabled={!chosenCabinet}
            onClick={() => setEditing({ kind: 'create', parentId: null })}>{t('folders.newTop')}</Button>
        </div>
        <ErrorAlert error={folders.error} />
        {tree.length === 0 && !folders.isLoading ? (
          <EmptyState title={t('folders.none')} description={t('folders.noneHint')} />
        ) : (
          <Tree key={`${chosenCabinet}:${all.length}`} className="jdms-folder-tree" treeData={tree} defaultExpandAll showIcon blockNode
            icon={({ expanded }: { expanded?: boolean }) => (expanded ? <FolderOpenOutlined aria-hidden /> : <FolderOutlined aria-hidden />)}
            selectedKeys={selectedId ? [selectedId] : []}
            onSelect={(keys) => { setSelectedId(keys[0] as string | undefined); setEditing(null); }} />
        )}
      </Card>
      <Card className="jdms-detail">
        {editing?.kind === 'create' && chosenCabinet ? (
          <FolderEditor cabinetId={chosenCabinet} folders={all} parentId={editing.parentId} onDone={() => setEditing(null)} />
        ) : editing?.kind === 'edit' && selected && chosenCabinet ? (
          <FolderEditor key={selected.id} cabinetId={chosenCabinet} folders={all} folder={selected} onDone={() => setEditing(null)} />
        ) : selected ? (
          <FolderDetail folder={selected} folders={all} onCreateChild={() => setEditing({ kind: 'create', parentId: selected.id })}
            onEdit={() => setEditing({ kind: 'edit' })} />
        ) : <EmptyState title={t('folders.selectOne')} />}
      </Card>
    </div>
  );
}

function FolderDetail({ folder, folders, onCreateChild, onEdit }: {
  folder: Folder;
  folders: Folder[];
  onCreateChild: () => void;
  onEdit: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const children = folders.filter((candidate) => candidate.parentId === folder.id).length;
  const remove = useMutation({
    mutationFn: async () => {
      const current = await api<FolderVersion>(`/admin/folders/${folder.id}`);
      return api<PendingChange>(`/admin/folders/${folder.id}`, { method: 'DELETE', idempotent: true, headers: ifMatch(current.version) });
    },
    onSuccess: () => {
      void message.success(t('admin.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
    },
  });
  return (
    <section aria-label={t('folders.detail')} className="jdms-type-detail">
      <div className="jdms-type-head">
        <div>
          <div className="jdms-muted jdms-small"><bdi>{folder.path}</bdi></div>
          <h2 className="jdms-properties-title">{folder.name}</h2>
        </div>
      </div>
      <FactList headingLevel={3} sections={[{ key: 'facts', facts: [
        { key: 'path', label: t('folders.path'), value: <code>{folder.path}</code> },
        { key: 'children', label: t('folders.subfolders'), value: children },
      ] }]} />
      <p className="jdms-muted">{t('folders.pathHint')}</p>
      <ErrorAlert error={remove.error} />
      <Space wrap>
        <Button icon={<PlusOutlined aria-hidden />} onClick={onCreateChild}>{t('folders.newSub')}</Button>
        <Button onClick={onEdit}>{t('folders.renameMove')}</Button>
        <Button danger disabled={children > 0} loading={remove.isPending} onClick={() => remove.mutate()}>{t('folders.delete')}</Button>
      </Space>
      {children > 0 ? <p className="jdms-muted">{t('folders.deleteHint')}</p> : null}
    </section>
  );
}

function FolderEditor({ cabinetId, folders, folder, parentId, onDone }: {
  cabinetId: string;
  folders: Folder[];
  folder?: Folder;
  parentId?: string | null;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: async (values: { name: string; parentId?: string | null }) => {
      const body = { name: values.name.trim(), parentId: values.parentId ?? null };
      if (!folder) {
        return api<PendingChange>(`/admin/cabinets/${cabinetId}/folders`, { method: 'POST', idempotent: true, body });
      }
      const current = await api<FolderVersion>(`/admin/folders/${folder.id}`);
      return api<PendingChange>(`/admin/folders/${folder.id}`, { method: 'PUT', idempotent: true, headers: ifMatch(current.version), body });
    },
    onSuccess: () => {
      void message.success(t('admin.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
      onDone();
    },
  });
  const parentPath = (id: string | null | undefined) => folders.find((candidate) => candidate.id === id)?.path ?? '/';
  const title = folder ? t('folders.renameMove') : t('folders.newIn', { path: parentPath(parentId) });
  return (
    <section aria-label={title} className="jdms-type-detail">
      <h2 className="jdms-properties-title">{title}</h2>
      <Form layout="vertical" onFinish={(values: { name: string; parentId?: string | null }) => save.mutate(values)}
        initialValues={{ name: folder?.name ?? '', parentId: folder ? folder.parentId : parentId }}>
        <Form.Item name="name" label={t('folders.name')}
          rules={[{ required: true, whitespace: true, message: t('upload.required', { field: t('folders.name') }) },
            { pattern: /^[^/]+$/, message: t('folders.noSlash') }]}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="parentId" label={t('folders.parent')} extra={t('folders.parentHint')}>
          <TreeSelect allowClear treeDefaultExpandAll aria-label={t('folders.parent')} placeholder={t('folders.top')}
            treeData={folderTree(folders, null, folder?.id)} />
        </Form.Item>
        <p className="jdms-muted">{t('folders.approvalNote')}</p>
        <ErrorAlert error={save.error} />
        <Space wrap>
          <Button type="primary" htmlType="submit" loading={save.isPending}>{t('admin.propose')}</Button>
          <Button onClick={onDone}>{t('common.cancel')}</Button>
        </Space>
      </Form>
    </section>
  );
}
