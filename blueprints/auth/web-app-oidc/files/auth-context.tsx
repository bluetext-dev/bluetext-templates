import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { authConfig } from "./auth-config";
import { decodeJwt, deriveChallenge, randomString, type JwtClaims } from "./pkce";

const STORAGE_KEY = "bluetext.auth";
const PKCE_KEY = "bluetext.auth.pkce";

interface StoredTokens {
  accessToken: string;
  idToken: string;
  refreshToken?: string;
}

interface PendingPkce {
  verifier: string;
  state: string;
  returnTo: string;
}

interface AuthContextValue {
  tokens: StoredTokens | null;
  claims: JwtClaims | null;
  roles: string[];
  hasRole: (role: string) => boolean;
  isAuthenticated: boolean;
  /** Start the OIDC login by redirecting to Curity. */
  login: (returnTo?: string) => Promise<void>;
  /** Handle an authorization-code callback. Returns true on success. */
  finishLogin: (params: URLSearchParams) => Promise<boolean>;
  /** Clear local tokens and end the Curity session. */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function readTokens(): StoredTokens | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StoredTokens;
  } catch {
    return null;
  }
}

function writeTokens(tokens: StoredTokens | null) {
  if (typeof window === "undefined") return;
  if (!tokens) window.localStorage.removeItem(STORAGE_KEY);
  else window.localStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
}

function readPending(): PendingPkce | null {
  if (typeof window === "undefined") return null;
  const raw = window.sessionStorage.getItem(PKCE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as PendingPkce;
  } catch {
    return null;
  }
}

function writePending(p: PendingPkce | null) {
  if (typeof window === "undefined") return;
  if (!p) window.sessionStorage.removeItem(PKCE_KEY);
  else window.sessionStorage.setItem(PKCE_KEY, JSON.stringify(p));
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [tokens, setTokens] = useState<StoredTokens | null>(() => readTokens());

  // Keep tokens in sync if another tab logs in/out.
  useEffect(() => {
    if (typeof window === "undefined") return;
    const onStorage = (e: StorageEvent) => {
      if (e.key === STORAGE_KEY) setTokens(readTokens());
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const claims = useMemo<JwtClaims | null>(() => {
    if (!tokens) return null;
    return decodeJwt(tokens.idToken) ?? decodeJwt(tokens.accessToken);
  }, [tokens]);

  const roles = useMemo<string[]>(() => {
    const r = claims?.roles;
    return Array.isArray(r) ? r.filter((x): x is string => typeof x === "string") : [];
  }, [claims]);

  const hasRole = useCallback((role: string) => roles.includes(role), [roles]);

  const login = useCallback(async (returnTo?: string) => {
    const verifier = randomString(48);
    const challenge = await deriveChallenge(verifier);
    const state = randomString(16);
    writePending({
      verifier,
      state,
      returnTo: returnTo ?? window.location.pathname + window.location.search,
    });
    const url = new URL(authConfig.authorizeEndpoint());
    url.searchParams.set("client_id", authConfig.clientId);
    url.searchParams.set("redirect_uri", authConfig.redirectUri());
    url.searchParams.set("response_type", "code");
    url.searchParams.set("scope", authConfig.scopes.join(" "));
    url.searchParams.set("code_challenge", challenge);
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("state", state);
    url.searchParams.set("nonce", randomString(16));
    window.location.assign(url.toString());
  }, []);

  const finishLogin = useCallback(async (params: URLSearchParams): Promise<boolean> => {
    const code = params.get("code");
    const returnedState = params.get("state");
    const pending = readPending();
    if (!code || !pending || returnedState !== pending.state) {
      writePending(null);
      return false;
    }
    const body = new URLSearchParams();
    body.set("grant_type", "authorization_code");
    body.set("client_id", authConfig.clientId);
    body.set("redirect_uri", authConfig.redirectUri());
    body.set("code", code);
    body.set("code_verifier", pending.verifier);
    const resp = await fetch(authConfig.tokenEndpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    writePending(null);
    if (!resp.ok) return false;
    const data = (await resp.json()) as {
      access_token: string;
      id_token: string;
      refresh_token?: string;
    };
    const next: StoredTokens = {
      accessToken: data.access_token,
      idToken: data.id_token,
      refreshToken: data.refresh_token,
    };
    writeTokens(next);
    setTokens(next);
    // Restore the page the user was on before the login redirect.
    if (pending.returnTo && pending.returnTo !== window.location.pathname) {
      window.history.replaceState({}, "", pending.returnTo);
    }
    return true;
  }, []);

  const logout = useCallback(async () => {
    // Clear local tokens and bounce home. Curity's session cookie is left
    // alone — the next "Sign in" click runs through the IdP again and Curity
    // will SSO-resume if the cookie is still valid (so swap users by clearing
    // browser cookies, or use Curity's own logout page).
    writeTokens(null);
    setTokens(null);
    window.location.assign("/");
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      tokens,
      claims,
      roles,
      hasRole,
      isAuthenticated: !!tokens,
      login,
      finishLogin,
      logout,
    }),
    [tokens, claims, roles, hasRole, login, finishLogin, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
