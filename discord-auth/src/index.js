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

export default {
      async fetch(request, env) {
            const url = new URL(request.url);

            // Health check
            if (url.pathname === "/") {
                  return json({ service: "ZeroFuel Discord Auth", status: "online" });
            }

            // Start Discord OAuth
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

            // Discord OAuth callback
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

                  const tokenResponse = await fetch(
                        "https://discord.com/api/oauth2/token",
                        {
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
                        }
                  );

                  if (!tokenResponse.ok) {
                        return json({ error: "Discord authorization failed" }, 401);
                  }

                  const tokenData = await tokenResponse.json();

                  const headers = {
                        Authorization: `Bearer ${tokenData.access_token}`,
                  };

                  const [userResponse, guildResponse] = await Promise.all([
                        fetch("https://discord.com/api/users/@me", { headers }),
                        fetch("https://discord.com/api/users/@me/guilds", { headers }),
                  ]);

                  if (!userResponse.ok || !guildResponse.ok) {
                        return json({ error: "Could not verify Discord account" }, 401);
                  }

                  const user = await userResponse.json();
                  const guilds = await guildResponse.json();

                  const isMember = guilds.some(
                        (guild) => guild.id === env.DISCORD_GUILD_ID
                  );

                  const handoffCode = randomToken();

                  await env.SESSIONS.put(
                        `handoff:${handoffCode}`,
                        JSON.stringify({
                              appState: pending.appState,
                              challenge: pending.challenge,
                              userId: user.id,
                              username: user.username,
                              isMember,
                        }),
                        { expirationTtl: 120 }
                  );

                  const appRedirect = new URL(env.APP_REDIRECT_URI);
                  appRedirect.searchParams.set("code", handoffCode);
                  appRedirect.searchParams.set("app_state", pending.appState);

                  return Response.redirect(appRedirect.toString(), 302);
            }

            // Exchange one-time code for app session
            if (url.pathname === "/auth/exchange" && request.method === "POST") {
                  const body = await request.json();

                  const { code, appState, codeVerifier } = body;

                  if (!code || !appState || !codeVerifier) {
                        return json({ error: "Missing exchange parameters" }, 400);
                  }

                  const saved = await env.SESSIONS.get(`handoff:${code}`);

                  if (!saved) {
                        return json({ error: "Invalid or expired code" }, 400);
                  }

                  await env.SESSIONS.delete(`handoff:${code}`);

                  const handoff = JSON.parse(saved);

                  if (handoff.appState !== appState) {
                        return json({ error: "State mismatch" }, 400);
                  }

                  const digest = await crypto.subtle.digest(
                        "SHA-256",
                        new TextEncoder().encode(codeVerifier)
                  );

                  const challenge = btoa(
                        String.fromCharCode(...new Uint8Array(digest))
                  )
                        .replace(/\+/g, "-")
                        .replace(/\//g, "_")
                        .replace(/=+$/, "");

                  if (challenge !== handoff.challenge) {
                        return json({ error: "PKCE verification failed" }, 401);
                  }

                  if (!handoff.isMember) {
                        return json({ authorized: false, reason: "not_a_member" });
                  }

                  const sessionToken = randomToken();

                  await env.SESSIONS.put(
                        `session:${sessionToken}`,
                        JSON.stringify({
                              userId: handoff.userId,
                              username: handoff.username,
                        }),
                        { expirationTtl: 604800 }
                  );

                  return json({
                        authorized: true,
                        sessionToken,
                        username: handoff.username,
                  });
            }

            // Verify an existing app session
            if (url.pathname === "/api/verify") {
                  const token = request.headers
                        .get("Authorization")
                        ?.replace(/^Bearer\s+/i, "");

                  if (!token) {
                        return json({ authorized: false }, 401);
                  }

                  const session = await env.SESSIONS.get(`session:${token}`);

                  if (!session) {
                        return json({ authorized: false }, 401);
                  }

                  return json({
                        authorized: true,
                        user: JSON.parse(session),
                  });
            }

            return json({ error: "Not found" }, 404);
      },
};