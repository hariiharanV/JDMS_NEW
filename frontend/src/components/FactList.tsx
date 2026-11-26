import type { ReactNode } from 'react';

export interface Fact {
  key: string;
  label: ReactNode;
  value: ReactNode;
}

export interface FactSection {
  key: string;
  /** A small heading over the section; sections after the first get a divider above. */
  title?: ReactNode;
  facts: Fact[];
}

/** Labelled values in a two-column grid, one column on a phone (UI handoff §4.7). */
export function FactList({ sections, headingLevel = 2 }: { sections: FactSection[]; headingLevel?: 2 | 3 }) {
  const Heading = headingLevel === 2 ? 'h2' : 'h3';
  return (
    <div className="jdms-facts">
      {sections.filter((section) => section.facts.length > 0).map((section) => (
        <section key={section.key} className="jdms-facts-section">
          {section.title ? <Heading className="jdms-facts-title">{section.title}</Heading> : null}
          <dl className="jdms-facts-grid">
            {section.facts.map((fact) => (
              <div key={fact.key} className="jdms-fact">
                <dt>{fact.label}</dt>
                <dd>{fact.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
    </div>
  );
}
