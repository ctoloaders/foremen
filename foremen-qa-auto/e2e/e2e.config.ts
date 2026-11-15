import type { E2EConfig } from 'e2e';
import { web } from '@e2e-dev/web';

// The suite drives the live Docker stack (`docker compose up` in the repo root):
// frontend :3000, backend :8080. Nothing is started by e2e itself.
const baseUrl = process.env.QA_BASE_URL ?? 'http://localhost:3000';

export default {
  tests: 'tests/**/*.e2e.ts',
  targets: [
    {
      name: 'web',
      // PL is the default UI language; the tests pin it so locators do not depend on the machine locale.
      engine: web({ locale: 'pl-PL', timezoneId: 'Europe/Warsaw', viewport: { width: 1440, height: 900 } }),
      app: { url: baseUrl, environment: 'test' },
    },
  ],
  credentials: {
    // Seeded ADMIN from docker-compose.yml (local dev defaults). Override via
    // E2E_USER_ADMIN_USERNAME / E2E_USER_ADMIN_PASSWORD.
    admin: {
      username: process.env.FOREMEN_ADMIN_EMAIL ?? 'cto+1@loaders.dev',
      password: process.env.FOREMEN_ADMIN_PASSWORD ?? 'alejano123',
    },
  },
  reporters: ['list', 'markdown'],
  workers: 1,
  retries: 0,
} satisfies E2EConfig;
