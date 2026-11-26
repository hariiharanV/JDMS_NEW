import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { apiBlob } from '../api/client';
import type { DocumentSummary } from '../api/types';

/** The current version's thumbnail, fetched with the user's token; nothing when there is none yet. */
export function Thumbnail({ document }: { document: DocumentSummary }) {
  const { t } = useTranslation();
  const [url, setUrl] = useState<string>();
  const version = document.currentVersion;

  useEffect(() => {
    if (!version || version.processingStatus !== 'DONE') {
      return;
    }
    let objectUrl: string | undefined;
    let cancelled = false;
    apiBlob(`/documents/${document.id}/versions/${version.id}/thumbnail`)
      .then((blob) => {
        if (!cancelled) {
          objectUrl = URL.createObjectURL(blob);
          setUrl(objectUrl);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      setUrl(undefined);
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [document.id, version]);

  return url ? <img className="thumbnail" src={url} alt={t('document.thumbnail', { title: document.title })} /> : null;
}
