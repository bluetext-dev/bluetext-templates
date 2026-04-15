# Multi-stage build for production-ready React Router (v7) app
# Stage 1: build optimized bundle with Bun (fast installer + bundler)
FROM oven/bun:latest AS builder
WORKDIR /app

# Copy manifests first so this layer caches across source changes
COPY package.json ./
# TODO: add bun.lock to the template and switch to `bun install --frozen-lockfile`
RUN bun install

# Copy source and build
COPY . .
RUN bun run build

# Stage 2: Node.js runtime — react-router-serve relies on node-only APIs
# (renderToPipeableStream is not exposed by Bun's react-dom/server.bun.js)
FROM node:22-slim
WORKDIR /app

COPY --from=builder /app/build ./build
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./

ENV NODE_ENV=production
ENV PORT=3000
EXPOSE 3000
# Runs `react-router-serve ./build/server/index.js` (production server, not Vite dev)
CMD ["npm", "run", "start"]
