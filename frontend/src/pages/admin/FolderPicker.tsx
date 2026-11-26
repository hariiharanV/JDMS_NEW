import { useState } from 'react';
import { Select, Space, TreeSelect } from 'antd';
import { useTranslation } from 'react-i18next';
import { useCabinets, useFolders } from '../../api/queries';
import { folderTree } from './folderTree';

/**
 * A folder chosen from a cabinet's tree (B-19), as a form control whose value is the folder's ID: used where a rule
 * applies to a folder and its sub-folders, instead of pasting the ID.
 */
export function FolderPicker({ value, onChange, label }: { value?: string; onChange?: (id: string | undefined) => void; label: string }) {
  const { t } = useTranslation();
  const cabinets = useCabinets();
  const [cabinetId, setCabinetId] = useState<string>();
  const chosen = cabinetId ?? cabinets.data?.[0]?.id;
  const folders = useFolders(chosen);
  return (
    <Space.Compact className="full-width jdms-folder-picker">
      <Select aria-label={t('browse.cabinet')} value={chosen} className="jdms-folder-picker-cabinet"
        onChange={(id: string) => { setCabinetId(id); onChange?.(undefined); }}
        options={(cabinets.data ?? []).map((cabinet) => ({ value: cabinet.id, label: cabinet.name }))} />
      <TreeSelect aria-label={label} value={value} onChange={(id: string) => onChange?.(id)} allowClear treeDefaultExpandAll
        placeholder={t('folders.choose')} treeData={folderTree(folders.data ?? [])} className="jdms-folder-picker-tree" />
    </Space.Compact>
  );
}
