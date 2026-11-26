import { Link } from 'react-router';
import { useDocumentTypes } from '../api/queries';
import { useLocalizedLabel } from '../i18n/labels';

/** A list's first column: the title as a link, the document type underneath (UI handoff §4.6). */
export function DocumentTitle({ id, title, typeCode }: { id: string; title: string; typeCode: string }) {
  const label = useLocalizedLabel();
  const types = useDocumentTypes();
  const type = types.data?.find((candidate) => candidate.code === typeCode);
  return (
    <span className="jdms-title-cell">
      <Link to={`/documents/${id}`}><bdi>{title}</bdi></Link>
      <span className="jdms-title-sub">{type ? label(type) : typeCode}</span>
    </span>
  );
}
