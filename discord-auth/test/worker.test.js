import test from "node:test";
import assert from "node:assert/strict";
import {
      isGuildMember,
      remainingTtl,
      sessionIsExpired,
} from "../src/index.js";

test("membership only matches the configured guild", () => {
      const guilds = [
            { id: "other" },
            { id: "1574355202629304322" },
      ];
      assert.equal(isGuildMember(guilds, "1574355202629304322"), true);
      assert.equal(isGuildMember(guilds, "someone-else"), false);
      assert.equal(isGuildMember(null, "1574355202629304322"), false);
});

test("sessions expire at their stored expiry", () => {
      const now = 1_700_000_000_000;
      assert.equal(sessionIsExpired({ sessionExpiresAt: now / 1000 + 10 }, now), false);
      assert.equal(sessionIsExpired({ sessionExpiresAt: now / 1000 - 1 }, now), true);
      assert.equal(sessionIsExpired({}, now), true);
});

test("KV TTL stays within the worker's seven-day window", () => {
      const nowSeconds = Math.floor(Date.now() / 1000);
      assert.equal(remainingTtl(nowSeconds + 3600), 3600);
      assert.equal(remainingTtl(nowSeconds - 100), 60);
      assert.equal(remainingTtl(nowSeconds + 999_999), 604800);
});
