import { session } from './shot.mjs';
{
  const s = await session('buyer@loaders.dev');
  await s.go('/deliveries');
  await s.page.getByText('Tablica zakupowca').click();
  await s.page.locator('[role=combobox]').filter({ hasText: 'Moje' }).first().click();
  await s.page.getByRole('option', { name: 'Wszyscy' }).click();
  await s.page.waitForTimeout(1500);
  await s.snap('buy-01-kanban');
  await s.page.locator('button:has(svg.lucide-bell)').first().click().catch(() => {});
  await s.snap('buy-08-notifications', { wait: 1500 });
  await s.close();
}
{
  const s = await session('oleg@loaders.dev');
  await s.go('/projects/2/positionApprovals');
  await s.page.locator('button:has(svg.lucide-bell)').first().click().catch(() => {});
  await s.snap('cli-09-notifications', { wait: 1500 });
  await s.close();
}
{
  const s = await session('piotr@loaders.dev');
  await s.go('/company/bank-transactions');
  await s.page.locator('button:has(svg.lucide-bell)').first().click().catch(() => {});
  await s.snap('fin-09-notifications', { wait: 1500 });
  await s.go('/deliveries/external-orders');
  await s.snap('fin-04-orders-queue', { wait: 1800 });
  await s.close();
}
