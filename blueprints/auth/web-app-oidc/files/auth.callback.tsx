import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { useAuth } from "~/lib/auth/auth-context";

/**
 * /auth/callback — Curity redirects here with ?code=...&state=...
 * We exchange the code for tokens and bounce home.
 */
export default function AuthCallback() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("error")) {
      setError(`${params.get("error")}: ${params.get("error_description") ?? ""}`);
      return;
    }
    auth
      .finishLogin(params)
      .then((ok) => {
        if (ok) navigate("/", { replace: true });
        else setError("Login failed — token exchange did not succeed.");
      })
      .catch((e) => setError(String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      data-testid="auth-callback"
      className="min-h-screen flex items-center justify-center p-4"
    >
      {error ? (
        <div className="max-w-md text-center space-y-3" data-testid="auth-callback-error">
          <h1 className="text-2xl font-bold text-red-600">Login failed</h1>
          <p className="text-sm text-gray-600 dark:text-gray-400 break-all">{error}</p>
          <a className="underline" href="/">
            Back home
          </a>
        </div>
      ) : (
        <p data-testid="auth-callback-loading">Completing sign in…</p>
      )}
    </div>
  );
}
