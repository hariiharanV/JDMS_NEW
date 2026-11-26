import type { Segment } from '../api/types';

/**
 * A search snippet: the server sends text segments flagged as matches, never HTML, so matches are marked by React
 * elements and every character is rendered as text (CLAUDE.md rule 8).
 */
export function Snippet({ segments }: { segments: Segment[] }) {
  return (
    <p className="snippet" dir="auto">
      {segments.map((segment, index) => (segment.match ? <mark key={index}>{segment.text}</mark>
        : <span key={index}>{segment.text}</span>))}
    </p>
  );
}
