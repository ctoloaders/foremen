import { session } from './shot.mjs';
const s = await session('buyer@loaders.dev');
const { page, snap, go } = s;
await go('/deliveries/external-orders');
await page.getByText('LM-2026-119902').first().click();
await page.waitForTimeout(1500);
await page.getByRole('button', { name: 'Wyślij do płatności' }).first().click();
await page.waitForTimeout(600);
await page.getByRole('dialog').getByRole('button', { name: 'Wyślij do płatności' }).click().catch(async () => {
  await page.locator('[role=alertdialog] button', { hasText: 'Wyślij do płatności' }).click();
});
await page.waitForTimeout(2000);
await snap('buy-05-order-sent');
await go('/deliveries/shipments');
await page.getByRole('button', { name: 'Dodaj przesyłkę' }).click();
await page.waitForTimeout(1200);
await snap('buy-07-new-shipment-form', { wait: 1200 });
await s.close();
