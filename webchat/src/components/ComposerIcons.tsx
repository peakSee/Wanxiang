import type { SVGProps } from "react";

interface ComposerIconProps {
  className?: string;
}

/** 万象附件按钮图标。 */
export function ComposerAttachmentIcon({ className }: ComposerIconProps) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      fill="none"
      height="20"
      viewBox="0 0 24 24"
      width="20"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="2"
    >
      <path d="M4 9a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h4a1 1 0 0 1 1 1v4a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2v-4a1 1 0 0 1 1-1h4a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-4a1 1 0 0 1-1-1V4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4a1 1 0 0 1-1 1z" />
    </svg>
  );
}

function DarkSendArrow(props: SVGProps<SVGSVGElement>) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" {...props}>
      <path
        d="M12 19V6m0 0-5 5m5-5 5 5"
        fill="none"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2.2"
      />
    </svg>
  );
}

/** 万象发送按钮图标。 */
export function ComposerSendIcon({ className }: ComposerIconProps) {
  return (
    <span aria-hidden="true" className={`composer-action-visual${className ? ` ${className}` : ""}`}>
      <svg className="composer-action-light" height="26" viewBox="0 0 24 24" width="26">
        <rect x="2" y="2" width="20" height="20" rx="10" fill="url(#composer-send-gradient)" />
        <rect
          x="11.5293"
          y="16.939"
          width="9.41177"
          height="1.17647"
          rx="0.588235"
          transform="rotate(-90 11.5293 16.939)"
          fill="white"
        />
        <path
          d="M11.6504 7.19336C11.9086 6.93555 12.3269 6.93541 12.585 7.19336C12.5915 7.19988 12.5963 7.20813 12.6025 7.21484C12.6105 7.2221 12.6202 7.22763 12.6279 7.23535L16.042 10.6504C16.2997 10.9086 16.2999 11.3269 16.042 11.585C15.7838 11.8432 15.3646 11.8432 15.1064 11.585L12.1172 8.5957L9.12891 11.585C8.87072 11.8432 8.45155 11.8432 8.19336 11.585C7.93542 11.3269 7.93556 10.9086 8.19336 10.6504L11.6504 7.19336Z"
          fill="white"
        />
        <defs>
          <linearGradient
            id="composer-send-gradient"
            x1="4.82609"
            y1="-19.875"
            x2="39.2112"
            y2="-1.69333"
            gradientUnits="userSpaceOnUse"
          >
            <stop stopColor="#1930D9" />
            <stop offset="1" stopColor="#2DA5F0" />
          </linearGradient>
        </defs>
      </svg>
      <span className="composer-action-dark composer-dark-send">
        <DarkSendArrow />
      </span>
    </span>
  );
}

/** 万象停止按钮图标。 */
export function ComposerStopIcon({ className }: ComposerIconProps) {
  return (
    <span aria-hidden="true" className={`composer-action-visual stop${className ? ` ${className}` : ""}`}>
      <svg className="composer-action-light" height="24" viewBox="0 0 12 12" width="24">
        <circle
          cx="6"
          cy="6"
          r="5.58"
          fill="url(#composer-stop-fill)"
          stroke="url(#composer-stop-stroke)"
          strokeWidth="0.84"
        />
        <path
          d="M4.66628 3.6001H7.33294C8.04405 3.6001 8.39961 3.95565 8.39961 4.66676V7.33343C8.39961 8.04454 8.04405 8.4001 7.33294 8.4001H4.66628C3.95516 8.4001 3.59961 8.04454 3.59961 7.33343V4.66676C3.59961 3.95565 3.95516 3.6001 4.66628 3.6001Z"
          fill="white"
        />
        <defs>
          <linearGradient
            id="composer-stop-fill"
            x1="1.69565"
            y1="-13.125"
            x2="22.3267"
            y2="-2.216"
            gradientUnits="userSpaceOnUse"
          >
            <stop stopColor="#1930D9" />
            <stop offset="1" stopColor="#2DA5F0" />
          </linearGradient>
          <linearGradient
            id="composer-stop-stroke"
            x1="1.69565"
            y1="-13.125"
            x2="22.3267"
            y2="-2.216"
            gradientUnits="userSpaceOnUse"
          >
            <stop stopColor="#1930D9" />
            <stop offset="1" stopColor="#2DA5F0" />
          </linearGradient>
        </defs>
      </svg>
      <span className="composer-action-dark composer-dark-stop">
        <span />
      </span>
    </span>
  );
}
