const API_BASE = "https://discord.com/api";
const TOKEN_URL = `${API_BASE}/oauth2/token`;
const SESSION_TTL_SECONDS = 7 * 24 * 60 * 60;

const json = (data, status = 200) =>
      new Response(JSON.stringify(data), {
            status,
            headers: { "Content-Type": "application/json" },
      });

const randomToken = (bytes = 32) => {
      const data = crypto.getRandomValues(new Uint8Array(bytes));
      return btoa(String.fromCharCode(...data))
            .replace(/\+/g, "-")
            .replace(/\//g, "_")
            .replace(/=+$/, "");
};

const sha256 = async (value) => {
      const digest = await crypto.subtle.digest(
            "SHA-256",
            new TextEncoder().encode(value)
      );

      return btoa(String.fromCharCode(...new Uint8Array(digest)))
            .replace(/\+/g, "-")
            .replace(/\//g, "_")
            .replace(/=+$/, "");
};

const bearerToken = (request) =>
      request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "").trim() || "";

const secondsFromNow = (seconds) => Math.floor(Date.now() / 1000) + seconds;
const sessionExpiry = () => secondsFromNow(SESSION_TTL_SECONDS);

/** KV has a 60s floor; never extend a record past its original week. */
const remainingTtl = (expiresAtSeconds) =>
      Math.max(60, Math.min(SESSION_TTL_SECONDS, expiresAtSeconds - Math.floor(Date.now() / 1000)));

const sessionIsExpired = (session, now = Date.now()) =>
      !session?.sessionExpiresAt || session.sessionExpiresAt * 1000 <= now;

const isGuildMember = (guilds, guildId) =>
      Array.isArray(guilds) && guilds.some((guild) => guild && guild.id === guildId);

const readJson = async (response) => {
      try {
            return await response.json();
      } catch {
            return null;
      }
};

const persistSession = async (env, session) => {
      // Never resurrect an already-expired record with the 60s KV floor.
      if (sessionIsExpired(session)) return;
      await env.SESSIONS.put(
            `session:${session.sessionToken}`,
            JSON.stringify(session),
            { expirationTtl: remainingTtl(session.sessionExpiresAt) }
      );
};

const refreshDiscordToken = async (env, session) => {
      if (!session.refreshToken) return null;

      const response = await fetch(TOKEN_URL, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({
                  client_id: env.DISCORD_CLIENT_ID,
                  client_secret: env.DISCORD_CLIENT_SECRET,
                  grant_type: "refresh_token",
                  refresh_token: session.refreshToken,
            }),
      });

      if (!response.ok) return null;
      const data = await readJson(response);
      if (!data?.access_token) return null;

      return {
            ...session,
            accessToken: data.access_token,
            refreshToken: data.refresh_token || session.refreshToken,
            discordExpiresAt: secondsFromNow(Number(data.expires_in) || 3600),
      };
};

/**
 * A token alone is not proof of membership: the app rechecks on every play, so
 * the worker must do the same. 503 means "cannot tell", which lets the app use
 * a still-valid cached authorization while Discord is down.
 */
const checkGuildMembership = async (env, session) => {
      if (!session?.accessToken) return { status: "unavailable" };

      const original = session;
      let response = await fetch(`${API_BASE}/users/@me/guilds`, {
            headers: { Authorization: `Bearer ${session.accessToken}` },
      });

      if (response.status === 401) {
            const refreshed = await refreshDiscordToken(env, session);
            if (!refreshed) return { status: "unavailable" };
            session = refreshed;

            response = await fetch(`${API_BASE}/users/@me/guilds`, {
                  headers: { Authorization: `Bearer ${session.accessToken}` },
            });
      }

      if (response.status === 429 || response.status >= 500) {
            return { status: "unavailable" };
      }
      if (!response.ok) return { status: "unavailable" };

      const guilds = await readJson(response);
      if (guilds === null) return { status: "unavailable" };

      if (session !== original) {
            try {
                  await persistSession(env, session);
            } catch {
                  return { status: "unavailable" };
            }
      }

      return {
            status: isGuildMember(guilds, env.DISCORD_GUILD_ID) ? "member" : "not_member",
            session,
      };
};

export default {
      async fetch(request, env) {
            const url = new URL(request.url);

            try {
                  if (url.pathname === "/") {
                        return json({
                              service: "ZeroFuel Discord Auth",
                              status: "online",
                        });
                  }

                  // Start OAuth
                  if (url.pathname === "/auth/discord") {
                        const challenge = url.searchParams.get("code_challenge");
                        const appState = url.searchParams.get("app_state");

                        if (
                              !challenge ||
                              !/^[A-Za-z0-9_-]{43}$/.test(challenge) ||
                              !appState ||
                              appState.length > 200
                        ) {
                              return json({ error: "Invalid OAuth parameters" }, 400);
                        }

                        const state = randomToken();

                        await env.SESSIONS.put(
                              `oauth:${state}`,
                              JSON.stringify({ challenge, appState }),
                              { expirationTtl: 300 }
                        );

                        const discord = new URL(
                              "https://discord.com/oauth2/authorize"
                        );

                        discord.searchParams.set("client_id", env.DISCORD_CLIENT_ID);
                        discord.searchParams.set("redirect_uri", env.DISCORD_REDIRECT_URI);
                        discord.searchParams.set("response_type", "code");
                        discord.searchParams.set("scope", "identify guilds");
                        discord.searchParams.set("state", state);

                        return Response.redirect(discord.toString(), 302);
                  }

                  // Discord callback
                  if (url.pathname === "/auth/callback") {
                        const code = url.searchParams.get("code");
                        const state = url.searchParams.get("state");

                        if (!code || !state) {
                              return json({ error: "Missing OAuth code or state" }, 400);
                        }

                        const saved = await env.SESSIONS.get(`oauth:${state}`);
                        if (!saved) {
                              return json({ error: "OAuth session expired" }, 400);
                        }

                        await env.SESSIONS.delete(`oauth:${state}`);
                        const pending = JSON.parse(saved);

                        const tokenResponse = await fetch(TOKEN_URL, {
                              method: "POST",
                              headers: {
                                    "Content-Type": "application/x-www-form-urlencoded",
                              },
                              body: new URLSearchParams({
                                    client_id: env.DISCORD_CLIENT_ID,
                                    client_secret: env.DISCORD_CLIENT_SECRET,
                                    grant_type: "authorization_code",
                                    code,
                                    redirect_uri: env.DISCORD_REDIRECT_URI,
                              }),
                        });

                        if (!tokenResponse.ok) {
                              return json({
                                    error: "Discord token exchange failed",
                                    status: tokenResponse.status,
                              }, 401);
                        }

                        const tokenData = await readJson(tokenResponse);
                        if (!tokenData?.access_token) {
                              return json({ error: "Discord token exchange failed" }, 401);
                        }

                        const headers = {
                              Authorization: `Bearer ${tokenData.access_token}`,
                        };

                        const [userResponse, guildResponse] = await Promise.all([
                              fetch(`${API_BASE}/users/@me`, { headers }),
                              fetch(`${API_BASE}/users/@me/guilds`, { headers }),
                        ]);

                        if (!userResponse.ok || !guildResponse.ok) {
                              return json({
                                    error: "Discord account verification failed",
                              }, 401);
                        }

                        const user = await readJson(userResponse);
                        const guilds = await readJson(guildResponse);
                        if (!user?.id) {
                              return json({ error: "Discord account verification failed" }, 401);
                        }

                        const handoffCode = randomToken();

                        await env.SESSIONS.put(
                              `handoff:${handoffCode}`,
                              JSON.stringify({
                                    appState: pending.appState,
                                    challenge: pending.challenge,
                                    userId: user.id,
                                    username: user.username || user.global_name || "",
                                    isMember: isGuildMember(guilds, env.DISCORD_GUILD_ID),
                                    // Handed to /auth/exchange and then moved into
                                    // the session record so verify can recheck.
                                    accessToken: tokenData.access_token,
                                    refreshToken: tokenData.refresh_token || null,
                                    discordExpiresAt: secondsFromNow(
                                          Number(tokenData.expires_in) || 3600
                                    ),
                              }),
                              { expirationTtl: 120 }
                        );

                        const appRedirect = new URL(env.APP_REDIRECT_URI);
                        appRedirect.searchParams.set("code", handoffCode);
                        appRedirect.searchParams.set("app_state", pending.appState);

                        return Response.redirect(appRedirect.toString(), 302);
                  }

                  // Exchange one-time handoff code
                  if (
                        url.pathname === "/auth/exchange" &&
                        request.method === "POST"
                  ) {
                        const body = await readJson(request);
                        const { code, appState, codeVerifier } = body || {};

                        if (!code || !appState || !codeVerifier) {
                              return json({ error: "Missing exchange parameters" }, 400);
                        }

                        const saved = await env.SESSIONS.get(`handoff:${code}`);
                        if (!saved) {
                              return json({ error: "Invalid or expired code" }, 400);
                        }

                        const handoff = JSON.parse(saved);

                        if (handoff.appState !== appState) {
                              return json({ error: "State mismatch" }, 400);
                        }

                        if (await sha256(codeVerifier) !== handoff.challenge) {
                              return json({ error: "PKCE verification failed" }, 401);
                        }

                        // Only a request that proved the PKCE state consumes the
                        // one-time code; a malformed callback cannot burn it.
                        await env.SESSIONS.delete(`handoff:${code}`);

                        if (!handoff.isMember) {
                              return json({
                                    authorized: false,
                                    reason: "not_a_member",
                              }, 403);
                        }

                        if (!handoff.accessToken) {
                              return json({
                                    authorized: false,
                                    reason: "verification_unavailable",
                              }, 503);
                        }

                        const sessionToken = randomToken();
                        const session = {
                              sessionToken,
                              userId: handoff.userId,
                              username: handoff.username,
                              accessToken: handoff.accessToken,
                              refreshToken: handoff.refreshToken,
                              discordExpiresAt: handoff.discordExpiresAt,
                              sessionExpiresAt: sessionExpiry(),
                        };

                        await persistSession(env, session);

                        return json({
                              authorized: true,
                              sessionToken,
                              username: handoff.username,
                              expiresAt: session.sessionExpiresAt * 1000,
                        });
                  }

                  // Verify app session, including live guild membership
                  if (url.pathname === "/api/verify") {
                        if (request.method !== "GET") {
                              return json({ error: "Method not allowed" }, 405);
                        }

                        const token = bearerToken(request);
                        if (!token) {
                              return json({
                                    authorized: false,
                                    reason: "invalid_session",
                              }, 401);
                        }

                        const session = await env.SESSIONS.get(`session:${token}`);
                        if (!session || sessionIsExpired(JSON.parse(session))) {
                              await env.SESSIONS.delete(`session:${token}`);
                              return json({
                                    authorized: false,
                                    reason: "invalid_session",
                              }, 401);
                        }

                        const check = await checkGuildMembership(env, JSON.parse(session));
                        if (check.status === "not_member") {
                              await env.SESSIONS.delete(`session:${token}`);
                              return json({
                                    authorized: false,
                                    reason: "not_a_member",
                              }, 403);
                        }
                        if (check.status === "unavailable") {
                              return json({
                                    authorized: false,
                                    reason: "verification_unavailable",
                              }, 503);
                        }

                        const active = check.session;
                        return json({
                              authorized: true,
                              user: { id: active.userId, username: active.username },
                              expiresAt: active.sessionExpiresAt * 1000,
                        });
                  }

                  return json({ error: "Not found" }, 404);
            } catch (error) {
                  console.error("ZeroFuel Auth Error:", error);
                  return json({
                        error: "Authentication service error",
                  }, 500);
            }
      },
};

export { isGuildMember, remainingTtl, sessionIsExpired };
