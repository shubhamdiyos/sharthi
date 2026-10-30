# syntax=docker/dockerfile:1
# Saathi web — Next.js 16.4 / React 19.3 / TypeScript (System Design §2.1, D11/D12).
#
#   deps     → installed node_modules, shared by the other stages
#   dev      → what Compose runs with a source bind mount and `next dev`
#   builder  → `next build` with output: 'standalone'
#   runtime  → production image: node server only, no devDependencies, non-root
#
# The dev stage exists so `docker compose up` can hot-reload without rebuilding:
# Compose mounts apps/web over /app and keeps /app/node_modules in an anonymous
# volume, so an edit triggers Next's watcher and nothing else.

# ─── deps ─────────────────────────────────────────────────────────────────────
FROM node:24-alpine AS deps
WORKDIR /app
COPY apps/web/package.json package-lock.json* ./
# npm ci when a lockfile is committed (it must be, for reproducibility);
# npm install is the bootstrap path before the first lockfile exists.
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

# ─── dev ──────────────────────────────────────────────────────────────────────
FROM deps AS dev
COPY apps/web ./
ENV NODE_ENV=development
EXPOSE 3000
CMD ["npm", "run", "dev", "--", "-H", "0.0.0.0"]

# ─── builder ──────────────────────────────────────────────────────────────────
FROM deps AS builder
COPY apps/web ./
# NEXT_PUBLIC_* values are inlined at build time. They are browser-visible by
# construction, so this is the only place a public URL may come from, and no
# secret can ever be passed through an ARG here (System Design §2.2, §11.4).
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
RUN npm run build

# ─── runtime ──────────────────────────────────────────────────────────────────
FROM node:24-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app

RUN addgroup -g 10001 -S nodejs && adduser -u 10001 -S nextjs -G nodejs

# Standalone output: server.js plus only the traced dependencies.
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000
ENV HOSTNAME=0.0.0.0 PORT=3000

# next start handles SIGTERM; node is PID 1 in exec form.
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=4 \
  CMD wget -qO- http://127.0.0.1:3000/ >/dev/null || exit 1

CMD ["node", "server.js"]
