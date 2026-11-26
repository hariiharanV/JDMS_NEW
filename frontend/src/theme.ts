import type { ThemeConfig } from 'antd';

/**
 * JDMS Next visual identity (UI handoff §2): one navy frame, a deep action blue, and gold only for "you are here"
 * (wordmark, active tab ink bar, focus ring). Status inks are dark on tinted grounds (at least 4.5:1). Flat
 * surfaces with thin borders; the only shadow is a page in the viewer well. Colours match src/index.css.
 */
export const palette = {
  navy: '#13294B',
  navy700: '#22395F',
  navyLine: '#3A5078',
  onNavyMuted: '#C6D0DF',
  gold: '#E0B456',
  goldAccent: '#C79A3B',
  action: '#1D4E89',
  ink: '#1B2230',
  ink2: '#4A5363',
  ink3: '#5B6472',
  ground: '#F6F7F9',
  surface: '#FFFFFF',
  sunken: '#EEF0F3',
  footer: '#FAFBFC',
  selected: '#E8EEF7',
  border: '#DCE0E6',
  borderStrong: '#C9D2DE',
  divider: '#E8EBEF',
  row: '#F0F2F5',
  success: '#1F6B3A',
  warning: '#B07400',
  danger: '#9A2626',
} as const;

const LATIN = "'IBM Plex Sans', system-ui, sans-serif";
/** Plex Sans Arabic for Arabic script, Plex Sans for Latin text (names, references) inside Arabic screens. */
const ARABIC = "'IBM Plex Sans Arabic', 'IBM Plex Sans', system-ui, sans-serif";
const MONO = "ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace";

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** The language switcher on the navy header (light text on the dark track). */
export const onNavy: ThemeConfig = {
  components: {
    Segmented: { trackBg: palette.navy700, itemSelectedBg: '#FFFFFF', itemSelectedColor: palette.navy,
      itemColor: '#FFFFFF', itemHoverColor: '#FFFFFF', itemHoverBg: 'rgb(255 255 255 / 12%)' },
  },
};

export function themeFor(language: 'en' | 'ar'): ThemeConfig {
  return {
    token: {
      colorPrimary: palette.action,
      colorLink: palette.action,
      colorInfo: palette.action,
      colorSuccess: palette.success,
      colorWarning: palette.warning,
      colorError: palette.danger,
      colorText: palette.ink,
      colorTextSecondary: palette.ink2,
      colorTextTertiary: palette.ink3,
      // antd's placeholder grey is 1.8:1 on white; placeholders carry hints, so they meet 4.5:1 too
      colorTextPlaceholder: palette.ink3,
      colorBorder: palette.borderStrong,
      colorBorderSecondary: palette.border,
      colorSplit: palette.row,
      colorBgLayout: palette.ground,
      colorBgContainer: palette.surface,
      colorFillAlter: palette.footer,
      fontFamily: language === 'ar' ? ARABIC : LATIN,
      fontFamilyCode: MONO,
      fontSize: 14,
      fontSizeHeading1: 28,
      fontSizeHeading2: 22,
      fontSizeHeading3: 18,
      fontSizeHeading4: 16,
      fontWeightStrong: 600,
      borderRadius: 8,
      borderRadiusSM: 4,
      borderRadiusLG: 10,
      controlHeight: 40,
      controlHeightLG: 44,
      controlHeightSM: 32,
      boxShadow: 'none',
      boxShadowSecondary: '0 4px 16px rgba(20,30,50,.12)', // menus and popovers only
      boxShadowTertiary: 'none',
      controlOutline: 'transparent', // focus is the gold :focus-visible ring (index.css)
      lineWidthFocus: 0,
      motion: !prefersReducedMotion(),
    },
    components: {
      Layout: { headerBg: palette.navy, headerHeight: 64, headerPadding: '0 24px', siderBg: palette.surface,
        bodyBg: palette.ground },
      Card: { headerFontSize: 16, colorBorderSecondary: palette.border },
      Table: {
        headerBg: palette.surface, headerColor: palette.ink2, headerSplitColor: 'transparent',
        rowSelectedBg: palette.selected, rowSelectedHoverBg: '#E0E8F3', rowHoverBg: palette.ground,
        borderColor: palette.row, cellPaddingBlock: 12,
      },
      Tabs: { inkBarColor: palette.goldAccent, itemSelectedColor: palette.navy, itemColor: palette.ink2,
        itemHoverColor: palette.navy },
      Button: { fontWeight: 600, primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none',
        defaultBorderColor: palette.borderStrong },
      Segmented: { trackBg: palette.divider, itemSelectedBg: palette.surface, itemSelectedColor: palette.navy,
        itemColor: palette.ink },
      Tag: { defaultBg: palette.sunken, defaultColor: '#3F4754' },
    },
  };
}
