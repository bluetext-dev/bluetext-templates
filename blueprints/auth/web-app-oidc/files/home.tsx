import { useState } from "react";
import type { Route } from "./+types/home";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "~/components/ui/card";
import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { useAuth } from "~/lib/auth/auth-context";
import { apiFetch, type ApiResult } from "~/lib/auth/api-client";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Bluetext RBAC Demo" },
    { name: "description", content: "Role-based access control demo" },
  ];
}

interface CallRecord {
  label: string;
  method: string;
  path: string;
  result: ApiResult;
}

const ROLE_TONE: Record<string, string> = {
  admin: "bg-purple-600 text-white",
  moderator: "bg-blue-600 text-white",
};

export default function Home() {
  const { isAuthenticated, claims, roles, hasRole, login, logout } = useAuth();
  const [last, setLast] = useState<CallRecord | null>(null);
  const [busy, setBusy] = useState(false);

  async function call(label: string, path: string) {
    setBusy(true);
    try {
      const result = await apiFetch(path);
      setLast({ label, method: "GET", path, result });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-start justify-center bg-gray-50 dark:bg-gray-900 p-4 pt-12">
      <div className="max-w-2xl w-full space-y-6">
        <Card data-testid="identity-card">
          <CardHeader>
            <CardTitle className="text-3xl font-bold">Bluetext RBAC Demo</CardTitle>
            <CardDescription>
              Curity issues the token, Kong introspects it, the API enforces roles.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {!isAuthenticated ? (
              <div className="text-center space-y-3" data-testid="anonymous-state">
                <p className="text-gray-600 dark:text-gray-400">
                  You are not signed in. Public endpoints are reachable; protected ones return 401.
                </p>
                <Button
                  data-testid="sign-in"
                  onClick={() => void login("/")}
                  className="px-6"
                >
                  Sign in with Curity
                </Button>
              </div>
            ) : (
              <div className="space-y-3" data-testid="authenticated-state">
                <div>
                  <p className="text-sm text-gray-500 dark:text-gray-400">Subject</p>
                  <p className="font-mono text-sm break-all" data-testid="claim-sub">
                    {String(claims?.sub ?? "")}
                  </p>
                </div>
                {claims?.name && (
                  <div>
                    <p className="text-sm text-gray-500 dark:text-gray-400">Name</p>
                    <p data-testid="claim-name">{String(claims.name)}</p>
                  </div>
                )}
                {claims?.email && (
                  <div>
                    <p className="text-sm text-gray-500 dark:text-gray-400">Email</p>
                    <p data-testid="claim-email">{String(claims.email)}</p>
                  </div>
                )}
                <div>
                  <p className="text-sm text-gray-500 dark:text-gray-400 mb-1">Roles</p>
                  {roles.length > 0 ? (
                    <div className="flex gap-2 flex-wrap" data-testid="role-badges">
                      {roles.map((r) => (
                        <Badge
                          key={r}
                          data-testid={`role-${r}`}
                          className={ROLE_TONE[r] ?? "bg-gray-500 text-white"}
                        >
                          {r}
                        </Badge>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm italic text-gray-500" data-testid="no-roles">
                      No roles assigned
                    </p>
                  )}
                </div>
                <Button
                  variant="outline"
                  onClick={() => void logout()}
                  data-testid="sign-out"
                >
                  Sign out
                </Button>
              </div>
            )}
          </CardContent>
        </Card>

        <Card data-testid="actions-card">
          <CardHeader>
            <CardTitle>API actions</CardTitle>
            <CardDescription>
              Each button hits a route through the Kong gateway. Buttons gated on roles
              are hidden when the role is missing — the click below proves the API still
              rejects the call if you bypass the UI.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-2">
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => call("Public hello", "/api/hello")}
              data-testid="call-hello"
            >
              GET /hello
            </Button>
            <Button
              variant="outline"
              disabled={busy || !isAuthenticated}
              onClick={() => call("Profile", "/api/me")}
              data-testid="call-me"
            >
              GET /me
            </Button>
            {hasRole("admin") && (
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => call("Admin: list users", "/api/admin/users")}
                data-testid="call-admin"
              >
                GET /admin/users
              </Button>
            )}
            {(hasRole("moderator") || hasRole("admin")) && (
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => call("Moderate", "/api/moderate")}
                data-testid="call-moderate"
              >
                GET /moderate
              </Button>
            )}
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => call("Force admin (bypass UI)", "/api/admin/users")}
              data-testid="call-admin-force"
            >
              Force /admin (bypass UI)
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => call("Force moderate (bypass UI)", "/api/moderate")}
              data-testid="call-moderate-force"
            >
              Force /moderate (bypass UI)
            </Button>
          </CardContent>
        </Card>

        {last && (
          <Card data-testid="response-card">
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-3">
                <span>{last.label}</span>
                <Badge
                  data-testid="response-status"
                  className={
                    last.result.ok
                      ? "bg-green-600 text-white"
                      : "bg-red-600 text-white"
                  }
                >
                  {last.result.status} {last.result.ok ? "OK" : "FAIL"}
                </Badge>
              </CardTitle>
              <CardDescription>
                {last.method} {last.path}
              </CardDescription>
            </CardHeader>
            <CardContent>
              <pre
                data-testid="response-body"
                className="text-xs bg-gray-100 dark:bg-gray-800 p-3 rounded overflow-x-auto whitespace-pre-wrap"
              >
                {typeof last.result.body === "string"
                  ? last.result.body
                  : JSON.stringify(last.result.body, null, 2)}
              </pre>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
