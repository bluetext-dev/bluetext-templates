// Bun HTTP server for React Router v7
// Serves built static assets from build/client/ and routes SSR to the request handler.
import { createRequestHandler } from "react-router";

// @ts-expect-error - resolved at runtime from the build output
const build = await import("./build/server/index.js");

const handler = createRequestHandler(build, "production");

const port = Number(process.env.PORT) || 3000;

Bun.serve({
  port,
  async fetch(req) {
    const url = new URL(req.url);

    // Serve static assets from build/client/ (JS, CSS, images, etc.)
    if (url.pathname.startsWith("/assets/")) {
      const file = Bun.file(`./build/client${url.pathname}`);
      if (await file.exists()) {
        return new Response(file);
      }
    }

    // Serve public files (favicon, etc.)
    if (url.pathname !== "/" && !url.pathname.startsWith("/api/")) {
      const file = Bun.file(`./build/client${url.pathname}`);
      if (await file.exists()) {
        return new Response(file);
      }
    }

    // Everything else: SSR via React Router
    return handler(req);
  },
});

console.log(`[bun] React Router server listening on port ${port}`);
