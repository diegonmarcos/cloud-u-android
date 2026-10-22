import { Tooltip } from '@cloud-notes/component';
import {
  type CustomLottieProps,
  InternalLottie,
} from '@cloud-notes/component/internal-lottie';
import { useI18n } from '@cloud-notes/i18n';
import type { HTMLAttributes } from 'react';
import type React from 'react';
import { cloneElement, useState } from 'react';

import edgelessHover from './animation-data/edgeless-hover.json';
import pageHover from './animation-data/page-hover.json';

type HoverAnimateControllerProps = {
  active?: boolean;
  hide?: boolean;
  trash?: boolean;
  children: React.ReactElement<CustomLottieProps>;
} & HTMLAttributes<HTMLDivElement>;

const HoverAnimateController = ({
  children,
  ...props
}: HoverAnimateControllerProps) => {
  const [startAnimate, setStartAnimate] = useState(false);
  return (
    <div
      onMouseEnter={() => setStartAnimate(true)}
      onMouseLeave={() => setStartAnimate(false)}
      {...props}
    >
      {cloneElement(children, {
        isStopped: !startAnimate,
        speed: 1,
        width: 20,
        height: 20,
      })}
    </div>
  );
};

const pageLottieOptions = {
  loop: false,
  autoplay: false,
  animationData: pageHover,
  rendererSettings: {
    preserveAspectRatio: 'xMidYMid slice',
  },
};

const edgelessLottieOptions = {
  loop: false,
  autoplay: false,
  animationData: edgelessHover,
  rendererSettings: {
    preserveAspectRatio: 'xMidYMid slice',
  },
};

export const PageSwitchItem = (
  props: Omit<HoverAnimateControllerProps, 'children'>
) => {
  const t = useI18n();
  return (
    <Tooltip
      content={t['com.cloud-notes.header.mode-switch.page']()}
      shortcut={['$alt', 'S']}
      side="bottom"
    >
      <HoverAnimateController {...props}>
        <InternalLottie options={pageLottieOptions} />
      </HoverAnimateController>
    </Tooltip>
  );
};

export const EdgelessSwitchItem = (
  props: Omit<HoverAnimateControllerProps, 'children'>
) => {
  const t = useI18n();
  return (
    <Tooltip
      content={t['com.cloud-notes.header.mode-switch.edgeless']()}
      shortcut={['$alt', 'S']}
      side="bottom"
    >
      <HoverAnimateController {...props}>
        <InternalLottie options={edgelessLottieOptions} />
      </HoverAnimateController>
    </Tooltip>
  );
};
