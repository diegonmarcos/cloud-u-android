# Cloud Notes Monorepo Cli

## Start

```bash
yarn cloud-notes -h
```

### Run build command defined in package.json

```bash
yarn cloud-notes i18n build
# or
yarn build -p i18n
```

### Run dev command defined in package.json

```bash
yarn cloud-notes web dev
# or
yarn dev -p i18n
```

### Clean

```bash
yarn cloud-notes clean --dist --rust
# clean node_modules
yarn cloud-notes clean --node-modules
```

### Init

> Generate files that make the monorepo work properly, the per project codegen will not be included anymore

```bash
yarn cloud-notes init
```

## Tricks

### Define scripts to run a .ts files without manually wiring a TypeScript loader

`cloud-notes run` will automatically inject `tsx` for your scripts

```json
{
  "name": "@cloud-notes/demo",
  "scripts": {
    "dev": "node ./dev.ts"
  }
}
```

```bash
cloud-notes @cloud-notes/demo dev
```

or

```json
{
  "name": "@cloud-notes/demo",
  "scripts": {
    "dev": "r ./src/index.ts"
  },
  "devDependencies": {
    "@cloud-notes-tools/cli": "workspace:*"
  }
}
```

### Short your key presses

```bash
# af is also available for running the scripts
yarn af web build
```

#### by custom shell script

> personally, I use 'af'

create file `af` in the root of Cloud Notes project with the following content

```bash
#!/usr/bin/env sh
./tools/scripts/bin/runner.js cloud-notes.ts $@
```

or on windows:

```cmd
node "./tools/cli/bin/runner.js" cloud-notes.ts %*
```

and give it executable permission

```bash
chmod a+x ./af

# now you can run scripts with simply
./af web build
```

if you want to go further, but for vscode(or other forks) only, add the following to your `.vscode/settings.json`

```json
{
  "terminal.integrated.env.osx": {
    "PATH": "${env:PATH}:${cwd}"
  },
  "terminal.integrated.env.linux": {
    "PATH": "${env:PATH}:${cwd}"
  },
  "terminal.integrated.env.windows": {
    "PATH": "${env:PATH};${cwd}"
  }
}
```

restart all the integrated terminals and now you get:

```bash
af web build
```

```

```
