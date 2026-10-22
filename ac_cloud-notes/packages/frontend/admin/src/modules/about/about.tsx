import { buttonVariants } from '@cloud-notes/admin/components/ui/button';
import { Separator } from '@cloud-notes/admin/components/ui/separator';
import { cn } from '@cloud-notes/admin/utils';
import {
  AlbumIcon,
  ChevronRightIcon,
  GithubIcon,
  MailWarningIcon,
  UploadCloudIcon,
} from 'lucide-react';

type Channel = 'stable' | 'canary' | 'beta' | 'internal';

const appNames = {
  stable: 'Cloud Notes',
  canary: 'Cloud Notes Canary',
  beta: 'Cloud Notes Beta',
  internal: 'Cloud Notes Internal',
} satisfies Record<Channel, string>;
const appName = appNames[BUILD_CONFIG.appBuildType];

const links = [
  {
    href: BUILD_CONFIG.githubUrl,
    icon: <GithubIcon size={20} />,
    label: 'Star Cloud Notes on GitHub',
  },
  {
    href: BUILD_CONFIG.githubUrl,
    icon: <MailWarningIcon size={20} />,
    label: 'Report an Issue',
  },
  {
    href: 'https://notes.diegonmarcos.com/docs/self-host-cloud-notes',
    icon: <AlbumIcon size={20} />,
    label: 'Self-host Document',
  },
  {
    href: 'https://notes.diegonmarcos.com/pricing/?type=selfhost#table',
    icon: <UploadCloudIcon size={20} />,
    label: 'Upgrade to Team',
  },
];

export function AboutCloudNotes() {
  return (
    <div className="flex flex-col h-full gap-3 py-5 px-6 w-full">
      <div className="flex items-center">
        <span className="text-xl font-semibold">About Cloud Notes</span>
      </div>
      <div className="overflow-y-auto space-y-[10px]">
        <div className="flex flex-col rounded-md border">
          {links.map(({ href, icon, label }, index) => (
            <div key={label + index}>
              <a
                className={cn(
                  buttonVariants({ variant: 'ghost' }),
                  'justify-between cursor-pointer w-full'
                )}
                href={href}
                target="_blank"
                rel="noreferrer"
              >
                <div className="flex items-center gap-3">
                  {icon}
                  <span>{label}</span>
                </div>
                <div>
                  <ChevronRightIcon size={20} />
                </div>
              </a>
              {index < links.length - 1 && <Separator />}
            </div>
          ))}
        </div>
      </div>
      <div className="space-y-3 text-sm font-normal text-muted-foreground">
        <div>{`App Version: ${appName} ${BUILD_CONFIG.appVersion}`}</div>
        <div>{`Editor Version: ${BUILD_CONFIG.editorVersion}`}</div>
      </div>
    </div>
  );
}
