#!/usr/bin/env node
// Prints an APP_USERS entry for one user: `node scripts/hash-password.mjs alice`
// The password is read from a hidden prompt (or from stdin when piped), never from the command line,
// so it does not end up in shell history. Same format as src/lib/auth/password.ts.
import { randomBytes, scryptSync } from "node:crypto";
import { createInterface } from "node:readline";

const username = (process.argv[2] ?? "").trim().toLowerCase();
if (!/^[a-z0-9._-]{1,32}$/.test(username)) {
  console.error("Usage: node scripts/hash-password.mjs <username>   (a-z, 0-9, . _ -; max 32 chars)");
  process.exit(1);
}

function readPassword() {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: process.stdin.isTTY });
    if (process.stdin.isTTY) {
      process.stdout.write("Password (hidden): ");
      rl._writeToOutput = () => {};
    }
    rl.once("line", (line) => {
      rl.close();
      if (process.stdin.isTTY) process.stdout.write("\n");
      resolve(line);
    });
  });
}

const password = await readPassword();
if (password.length < 12) {
  console.error("Use at least 12 characters (a passphrase is easiest).");
  process.exit(1);
}
const N = 16384;
const salt = randomBytes(16);
const hash = scryptSync(password, salt, 32, { N, r: 8, p: 1 });
console.log(`${username}=scrypt:${N}:${salt.toString("base64url")}:${hash.toString("base64url")}`);
