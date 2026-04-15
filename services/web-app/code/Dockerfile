# Multi-stage build for production-ready React Router (v7) app on Bun
# Stage 1: install deps + build optimized bundle with Bun
FROM oven/bun:latest AS builder
WORKDIR /app

# Copy manifests first so this layer caches across source changes
COPY package.json ./
# TODO: add bun.lock to the template and switch to `bun install --frozen-lockfile`
RUN bun install

# Copy source and build
COPY . .
RUN bun run build

# Stage 2: slim Bun runtime
# Custom entry.server.tsx uses renderToReadableStream (Web Standard API,
# exported by react-dom/server.bun.js). server.ts wires Bun.serve to
# React Router's createRequestHandler — no Node runtime or
# @react-router/serve needed.
FROM oven/bun:slim
WORKDIR /app

COPY --from=builder /app/build ./build
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
COPY --from=builder /app/server.ts ./server.ts

ENV NODE_ENV=production
ENV PORT=3000
EXPOSE 3000
CMD ["bun", "run", "server.ts"]
