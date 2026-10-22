# Dev containers

## Develop with domain

> MacOs only, OrbStack only

### 1. Generate and install Root CA

```bash
# the root ca file will be located at `./.docker/dev/certs/ca`
yarn cloud-notes cert --install
```

### 2. Generate domain certs

```bash
# certificates will be located at `./.docker/dev/certs/${domain}`
yarn cloud-notes cert --domain cloud-notes.localhost
```

### 3. Enable nginx service in compose.yml
