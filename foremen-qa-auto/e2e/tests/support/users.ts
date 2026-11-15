import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import bcrypt from 'bcryptjs';

// Direct-DB test-user provisioning, the same approach as the Java suite's TestUserFixture:
// the app has no API that creates a user with a known password. Works against the local
// Docker stack only (psql runs inside the postgres container).
const POSTGRES_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';

export interface TestUser {
  id: number;
  email: string;
  password: string;
  roleCode: string;
}

/** Runs SQL with psql variables (`:'name'` is a quoted literal), so values are never concatenated. */
function psql(sql: string, vars: Record<string, string> = {}): string {
  const args = ['exec', '-i', POSTGRES_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}

/** Unique per run, so repeated runs never collide. */
export function runId(): string {
  return `${Date.now().toString(36)}${randomBytes(2).toString('hex')}`;
}

/** Creates an ACTIVE user with a known password in the given role. */
export function createUser(roleCode: string, tag = 'u'): TestUser {
  const id = runId();
  const email = `dash+${id}-${tag}@example.com`;
  const password = `Qa!${randomBytes(6).toString('hex')}`;
  const hash = bcrypt.hashSync(password, 12);
  const out = psql(
    `INSERT INTO users (name, email, phone, role_id, active, locale, status, password_hash, created_date, created_by)
     SELECT 'QA ' || :'email', :'email', NULL, r.id, true, 'pl', 'ACTIVE', :'hash', NOW(), 'qa'
     FROM roles r WHERE r.code = :'role'
     RETURNING id;`,
    { email, hash, role: roleCode },
  );
  const userId = Number(out.split('\n')[0]);
  if (!Number.isFinite(userId)) throw new Error(`createUser(${roleCode}) inserted nothing: role missing?`);
  return { id: userId, email, password, roleCode };
}

/** Removes the user and the FK-dependent rows that do not cascade. Idempotent. */
export function deleteUser(user: TestUser | undefined): void {
  if (!user) return;
  psql(
    `DELETE FROM project_members WHERE user_id = :uid;
     DELETE FROM refresh_tokens WHERE user_id = :uid;
     DELETE FROM invite_tokens WHERE user_id = :uid;
     DELETE FROM password_reset_tokens WHERE user_id = :uid;
     DELETE FROM users WHERE id = :uid;`,
    { uid: String(user.id) },
  );
}

/** Runs `body` with a fresh user and always deletes it afterwards. */
export async function withUser<T>(roleCode: string, body: (user: TestUser) => Promise<T>): Promise<T> {
  const user = createUser(roleCode);
  try {
    return await body(user);
  } finally {
    deleteUser(user);
  }
}
