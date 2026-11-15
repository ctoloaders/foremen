import { test } from '@e2e-dev/web';
import { expect, credentials } from 'e2e';

// Signs in once as the seeded ADMIN and shares the session with dependent tests.
test.setup('authenticate as admin', { sessions: ['admin'] }, async ({ app, screen, session }) => {
  const admin = credentials.user('admin');

  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(admin.username);
  await screen.getByRole('textbox', 'Hasło').fill(admin.password);
  await screen.getByRole('button', 'Zaloguj się').tap();

  // Prove the sign-in worked before saving the session.
  await expect(screen.getByRole('heading', 'Panel').first()).toBeVisible();
  await session.save('admin');
});
