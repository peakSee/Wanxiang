import type { ReactNode, SVGProps } from "react";

export type IconName =
  | "agent"
  | "arrow-left"
  | "arrow-right"
  | "chat"
  | "chevron-down"
  | "chevron-left"
  | "download"
  | "file"
  | "folder"
  | "menu"
  | "panel-left"
  | "panel-right"
  | "plus"
  | "refresh"
  | "save"
  | "search"
  | "shield"
  | "terminal"
  | "trash"
  | "workspace"
  | "x";

interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
  size?: number;
}

export function Icon({ name, size = 20, ...props }: IconProps) {
  const paths = {
    agent: (
      <>
        <path d="M12 8V4H8" />
        <rect width="16" height="12" x="4" y="8" rx="2" />
        <path d="M2 14h2M20 14h2M9 13v2M15 13v2" />
      </>
    ),
    "arrow-left": <path d="m12 19-7-7 7-7M19 12H5" />,
    "arrow-right": <path d="m12 5 7 7-7 7M5 12h14" />,
    chat: <path d="M22 17a2 2 0 0 1-2 2H6.8a2 2 0 0 0-1.4.6l-2.2 2.2A.7.7 0 0 1 2 21.3V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2Z" />,
    "chevron-down": <path d="m6 9 6 6 6-6" />,
    "chevron-left": <path d="m15 18-6-6 6-6" />,
    download: <path d="M12 3v12m0 0 4-4m-4 4-4-4M5 21h14" />,
    file: (
      <>
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
        <path d="M14 2v6h6" />
      </>
    ),
    folder: <path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />,
    menu: <path d="M3 5h12M3 12h18M3 19h16" />,
    "panel-left": (
      <>
        <rect width="18" height="18" x="3" y="3" rx="2" />
        <path d="M9 3v18" />
      </>
    ),
    "panel-right": (
      <>
        <rect width="18" height="18" x="3" y="3" rx="2" />
        <path d="M15 3v18" />
      </>
    ),
    plus: <path d="M12 5v14M5 12h14" />,
    refresh: (
      <>
        <path d="M20 6v5h-5" />
        <path d="M18.5 15a7 7 0 1 1 .2-6.2L20 11" />
      </>
    ),
    save: (
      <>
        <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z" />
        <path d="M17 21v-8H7v8M7 3v5h8" />
      </>
    ),
    search: <path d="m21 21-4.3-4.3m2.3-5.2a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" />,
    shield: <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />,
    terminal: (
      <>
        <path d="m4 17 6-6-6-6M12 19h8" />
      </>
    ),
    trash: (
      <>
        <path d="M3 6h18M8 6V4h8v2M19 6l-1 15H6L5 6M10 11v5M14 11v5" />
      </>
    ),
    workspace: <path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />,
    x: <path d="m18 6-12 12M6 6l12 12" />,
  } satisfies Record<IconName, ReactNode>;

  return (
    <svg
      aria-hidden="true"
      fill="none"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.8"
      {...props}
    >
      {paths[name]}
    </svg>
  );
}
