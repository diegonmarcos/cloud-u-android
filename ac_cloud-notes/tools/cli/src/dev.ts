import type { PackageName } from '@cloud-notes-tools/utils/workspace';

import { Option, PackageSelectorCommand } from './command';

export class DevCommand extends PackageSelectorCommand {
  static override paths = [['dev'], ['d']];

  protected override availablePackages: PackageName[] = [
    '@cloud-notes/web',
    '@cloud-notes/server',
    '@cloud-notes/electron',
    '@cloud-notes/electron-renderer',
    '@cloud-notes/mobile',
    '@cloud-notes/ios',
    '@cloud-notes/android',
    '@cloud-notes/admin',
  ];

  protected deps = Option.Boolean('--deps', {
    description: 'Run dev with dependencies',
  });

  async execute() {
    const name = await this.getPackage();
    const args = [];

    if (this.deps) {
      args.push('--deps');
    }

    args.push(name, 'dev');

    await this.cli.run(args);
  }
}
