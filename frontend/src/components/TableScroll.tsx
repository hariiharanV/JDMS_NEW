import { useEffect, useRef, type ReactNode } from 'react';

/**
 * Wraps a table that may scroll sideways (on a phone) and may hold nothing focusable (no links or buttons, or no
 * rows): its scroll area then takes keyboard focus, so it can be scrolled with the arrow keys (WCAG 2.1.1).
 */
export function TableScroll({ label, children }: { label: string; children: ReactNode }) {
  const wrapper = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const update = () => {
      wrapper.current?.querySelectorAll<HTMLElement>('.ant-table-content, .ant-table-body').forEach((area) => {
        if (area.scrollWidth > area.clientWidth) {
          area.tabIndex = 0;
          area.setAttribute('role', 'region');
          area.setAttribute('aria-label', label);
        } else {
          area.removeAttribute('tabindex');
          area.removeAttribute('role');
          area.removeAttribute('aria-label');
        }
      });
    };
    update();
    window.addEventListener('resize', update);
    const observer = new MutationObserver(update);
    if (wrapper.current) observer.observe(wrapper.current, { childList: true, subtree: true });
    return () => {
      window.removeEventListener('resize', update);
      observer.disconnect();
    };
  }, [label]);
  return <div ref={wrapper}>{children}</div>;
}
