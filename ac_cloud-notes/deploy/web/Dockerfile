# Cloud Notes web (Phase 1, cloud-notes-hybrid): the Cloud Notes web bundle of this
# repository served as static files. The container holds NO secret and NO
# account: it is reached only through the edge gate (Authelia 2FA); the sync
# socket (Phase 2) authenticates separately with a Bearer token.
#
# Built by the ship workflow's web_image job from
# packages/frontend/apps/web/dist (yarn cloud-notes @cloud-notes/web build).
FROM nginx:1.27-alpine
COPY deploy/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY packages/frontend/apps/web/dist/ /usr/share/nginx/html/
EXPOSE 80
