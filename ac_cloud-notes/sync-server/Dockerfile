# Cloud Notes sync server (Phase 2, cloud-notes-hybrid): Hocuspocus (MIT) over Yjs,
# SQLite persistence in /data, Bearer-authenticated. Our own code; never Cloud Notes's
# EE server. Reached only through the edge gate; refuses to start without a
# configured verifier (SYNC_INTROSPECT_URL or, for development, SYNC_STATIC_TOKENS).
FROM node:22-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY src ./src
RUN mkdir -p /data && chown node:node /data
ENV NODE_ENV=production PORT=1234 SYNC_DB=/data/sync.sqlite
VOLUME /data
USER node
EXPOSE 1234
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||1234)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "src/main.mjs"]
