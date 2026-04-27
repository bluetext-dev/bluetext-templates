/**
 * PKCE helpers for OIDC authorization code flow.
 *
 * Uses Web Crypto for SHA-256 (S256 challenge method) and crypto.getRandomValues
 * for entropy. base64url encoding follows RFC 4648 §5 — no padding, +/ replaced
 * by -_, which is what RFC 7636 mandates for the code_challenge.
 */

function base64UrlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomBytes(length: number): Uint8Array {
  const out = new Uint8Array(length);
  crypto.getRandomValues(out);
  return out;
}

export function randomString(byteLength = 32): string {
  return base64UrlEncode(randomBytes(byteLength));
}

export async function deriveChallenge(verifier: string): Promise<string> {
  const data = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return base64UrlEncode(new Uint8Array(digest));
}

export interface JwtClaims {
  sub?: string;
  email?: string;
  name?: string;
  roles?: string[];
  [k: string]: unknown;
}

/** Decode a JWT payload. Returns null on any parse error. */
export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    // atob expects padded base64.
    const padded = payload + "=".repeat((4 - (payload.length % 4)) % 4);
    const json = atob(padded);
    return JSON.parse(decodeURIComponent(escape(json))) as JwtClaims;
  } catch {
    return null;
  }
}
