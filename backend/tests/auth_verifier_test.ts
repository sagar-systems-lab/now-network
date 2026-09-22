import { SupabaseAuthVerifier } from "../src/auth.ts";
import { ApiFault } from "../src/errors.ts";

const USER_ID = "00000000-0000-4000-8000-0000000000a1";

function verifier(response: Response): SupabaseAuthVerifier {
  const fetchImpl: typeof fetch = () => Promise.resolve(response);
  return new SupabaseAuthVerifier("https://example.supabase.co", "anon-key", fetchImpl);
}

Deno.test("Supabase auth verifier requires a bearer token", async () => {
  const auth = verifier(Response.json({ id: USER_ID }));
  try {
    await auth.verify(new Request("http://localhost/v1/me"));
    throw new Error("missing bearer token unexpectedly passed");
  } catch (error) {
    if (!(error instanceof ApiFault) || error.code !== "AUTH_REQUIRED") throw error;
  }
});

Deno.test("Supabase auth verifier rejects an invalid or expired token", async () => {
  const auth = verifier(Response.json({ message: "invalid" }, { status: 401 }));
  const request = new Request("http://localhost/v1/me", {
    headers: { authorization: "Bearer expired-token" },
  });

  try {
    await auth.verify(request);
    throw new Error("invalid bearer token unexpectedly passed");
  } catch (error) {
    if (!(error instanceof ApiFault) || error.code !== "AUTH_INVALID") throw error;
  }
});

Deno.test("Supabase auth verifier resolves anonymous principal identity", async () => {
  let observedAuthorization = "";
  let observedApiKey = "";
  const fetchImpl: typeof fetch = (_input, init) => {
    const headers = new Headers(init?.headers);
    observedAuthorization = headers.get("authorization") ?? "";
    observedApiKey = headers.get("apikey") ?? "";
    return Promise.resolve(Response.json({ id: USER_ID, is_anonymous: true }));
  };
  const auth = new SupabaseAuthVerifier(
    "https://example.supabase.co/",
    "anon-key",
    fetchImpl,
  );
  const request = new Request("http://localhost/v1/me", {
    headers: { authorization: "Bearer valid-token" },
  });

  const principal = await auth.verify(request);
  if (principal.authUserId !== USER_ID) throw new Error("unexpected auth user");
  if (principal.principalType !== "SUPABASE_ANONYMOUS") {
    throw new Error("anonymous principal classification failed");
  }
  if (observedAuthorization !== "Bearer valid-token") {
    throw new Error("bearer token was not forwarded to Supabase Auth");
  }
  if (observedApiKey !== "anon-key") throw new Error("anon API key was not sent");
});

Deno.test("Supabase auth verifier rejects malformed user payloads", async () => {
  const auth = verifier(Response.json({ id: "not-a-uuid" }));
  const request = new Request("http://localhost/v1/me", {
    headers: { authorization: "Bearer valid-token" },
  });

  try {
    await auth.verify(request);
    throw new Error("malformed auth user unexpectedly passed");
  } catch (error) {
    if (!(error instanceof ApiFault) || error.code !== "AUTH_INVALID") throw error;
  }
});
