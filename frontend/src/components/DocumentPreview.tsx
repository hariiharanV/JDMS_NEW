import { useCallback, useState } from 'react';
import { Alert } from 'antd';
import { useTranslation } from 'react-i18next';
import { apiBlob } from '../api/client';
import { useMe } from '../api/queries';
import type { Classification, Version } from '../api/types';
import { useFormatters } from '../i18n/labels';
import { isClassified } from './classification';
import { DocumentViewer } from './DocumentViewer';

interface Props {
  documentId: string;
  version: Version | null | undefined;
  classification: Classification;
  /** The caller may see the content (VIEW_CONTENT on this document). */
  canView: boolean;
  compact?: boolean;
}

/**
 * The preview of one version, or why there is none: no right to the content, still processing, quarantined or
 * failed. Classified documents are watermarked with the viewer's name, the time and the classification.
 */
export function DocumentPreview({ documentId, version, classification, canView, compact }: Props) {
  const { t } = useTranslation();
  const format = useFormatters();
  const me = useMe();
  const [openedAt] = useState(() => new Date().toISOString());
  const versionId = version?.id ?? '';
  const load = useCallback(() => apiBlob(`/documents/${documentId}/versions/${versionId}/preview`), [documentId, versionId]);

  if (!canView) return <Alert type="info" showIcon title={t('viewer.notAllowed')} />;
  if (!version) return null;
  switch (version.processingStatus) {
    case 'QUARANTINED': return <Alert type="error" showIcon title={t('viewer.quarantined')} />;
    case 'FAILED': return <Alert type="error" showIcon title={t('viewer.failed')} />;
    case 'DONE': break;
    default: return <Alert type="info" showIcon title={t('viewer.processing')} />;
  }
  const viewer = me.data?.displayName ?? me.data?.username ?? '';
  const watermark = isClassified(classification)
    ? `${viewer} · ${format.dateTime(openedAt)} · ${t(`classification.${classification}`)}`
    : undefined;
  return <DocumentViewer load={load} fileName={version.originalFilename} watermark={watermark} compact={compact} />;
}
