# Multi-stage build for production-ready React Router (v7) app
# Stage 1: build optimized bundle
FROM oven/bun:latest AS builder
WORKDIR /app

# Copy manifests first so this layer caches across source changes
COPY package.json ./
# TODO: add bun.lock to the template and switch to `bun install --frozen-lockfile`
RUN bun install

# Copy source and build
COPY . .
RUN bun run build

# Stage 2: slim runtime — no dev tools, only what's needed to serve
FROM oven/bun:slim
WORKDIR /app

COPY --from=builder /app/build ./build
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./

ENV PORT=3000
EXPOSE 3000
# Runs `react-router-serve ./build/server/index.js` (production server, not Vite dev)
CMD ["bun", "run", "start"]
