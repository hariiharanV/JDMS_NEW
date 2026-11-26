import type { DataNode } from 'antd/es/tree';
import type { Folder } from '../../api/types';

/** The folders of one cabinet as a tree (children sorted by name). */
export function folderTree(folders: Folder[], parentId: string | null = null, exclude?: string): DataNode[] {
  return folders.filter((folder) => folder.parentId === parentId && folder.id !== exclude)
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((folder) => ({ key: folder.id, value: folder.id, title: folder.name,
      children: folderTree(folders, folder.id, exclude) }));
}
