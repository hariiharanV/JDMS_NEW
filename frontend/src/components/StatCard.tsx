import type { ReactNode } from 'react';
import { Link } from 'react-router';

interface Props {
  label: ReactNode;
  /** The number; undefined while loading. */
  value: number | undefined;
  hint?: ReactNode;
  /** Opens the filtered list behind the number: a route, or `#section` on the same page. */
  to: string;
  tone?: 'default' | 'warning';
  format: (value: number) => string;
}

/** A count that opens the list behind it (UI handoff §4.5). */
export function StatCard({ label, value, hint, to, tone = 'default', format }: Props) {
  const content = (
    <>
      <span className="jdms-stat-label">{label}</span>
      <span className="jdms-stat-value">{value === undefined ? '–' : format(value)}</span>
      {hint ? <span className="jdms-stat-hint">{hint}</span> : null}
    </>
  );
  const className = `jdms-stat jdms-stat-${tone}`;
  // a section of this page: a plain anchor, so the browser scrolls to it and moves focus there
  return to.startsWith('#') ? <a href={to} className={className}>{content}</a>
    : <Link to={to} className={className}>{content}</Link>;
}
