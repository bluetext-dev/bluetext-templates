import { useEffect, useState } from "react";
import type { Route } from "./+types/home";
import { apiUrl } from "~/lib/api";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "~/components/ui/card";
import { Badge } from "~/components/ui/badge";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Bluetext Cloud" },
    { name: "description", content: "Bluetext web application" },
  ];
}

export default function Home() {
  const [apiMessage, setApiMessage] = useState<string>("Loading...");
  const [apiConnected, setApiConnected] = useState<boolean | null>(null);

  useEffect(() => {
    // Reach the api through `apiUrl` (see app/lib/api.ts): in the browser it
    // resolves to the same-origin `/api/hello`, which the Vite proxy forwards to
    // the api's `GET /hello` — the call never crosses an origin boundary. Never
    // fetch the api's own subdomain (e.g. `api--<token>--<user>.<domain>`) from
    // the browser: that's cross-origin and gets blocked by the lab auth gateway
    // + CORS. See AGENTS.md.
    fetch(apiUrl("/hello"))
      .then((res) => res.json())
      .then((data) => {
        setApiMessage(data.message);
        setApiConnected(true);
      })
      .catch(() => {
        setApiMessage("Failed to reach API");
        setApiConnected(false);
      });
  }, []);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 p-4">
      <Card className="max-w-2xl w-full">
        <CardHeader className="text-center">
          <CardTitle className="text-3xl font-bold mb-2">
            hello from web-app
          </CardTitle>
          <CardDescription className="text-lg">
            {apiConnected === null && "Connecting to API..."}
            {apiConnected === true && apiMessage}
            {apiConnected === false && (
              <span className="text-red-500">{apiMessage}</span>
            )}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="text-center space-y-4">
            <p className="text-gray-600 dark:text-gray-400">
              This template includes everything you need to start building:
            </p>
            <div className="flex flex-wrap gap-2 justify-center">
              <Badge variant="secondary">React 19</Badge>
              <Badge variant="secondary">React Router 7</Badge>
              <Badge variant="secondary">Bun Runtime</Badge>
              <Badge variant="secondary">shadcn/ui Components</Badge>
              <Badge variant="secondary">Tailwind CSS</Badge>
              <Badge variant="secondary">TypeScript</Badge>
            </div>
          </div>

          <div className="text-center">
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Start building your application by editing the routes in the <code className="bg-gray-100 dark:bg-gray-700 px-1 rounded">app/</code> directory.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
