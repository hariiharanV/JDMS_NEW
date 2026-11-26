import type { MouseEvent } from 'react';
import { Button, type ButtonProps } from 'antd';
import { useHref, useLinkClickHandler } from 'react-router';

/**
 * A link that looks like a button (it goes somewhere, so it is a link to assistive technology and opens in a new tab
 * on a middle click), routed inside the app.
 */
export function LinkButton({ to, ...props }: Omit<ButtonProps, 'href' | 'onClick'> & { to: string }) {
  const href = useHref(to);
  const handleClick = useLinkClickHandler<HTMLAnchorElement>(to);
  return (
    <Button {...props} href={href}
      onClick={(event: MouseEvent<HTMLElement>) => handleClick(event as MouseEvent<HTMLAnchorElement>)} />
  );
}
