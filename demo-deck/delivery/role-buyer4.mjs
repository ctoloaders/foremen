import { session } from './shot.mjs';
const s = await session('buyer@loaders.dev');
const { page, snap, go } = s;
await go('/deliveries/shipments');
await page.waitForTimeout(1500);
for (const l of await page.locator('[data-testid^=shipment-tracking-link-]').all()) {
  console.log(await l.getAttribute('data-testid'), '->', await l.getAttribute('href'));
}
await snap('buy-06-shipments', { wait: 800 });
await page.locator('[data-testid^=shipment-open-]').last().click();
await snap('buy-09-shipment-drawer-link', { wait: 1500 });
const href = await page.locator('[data-testid=shipment-drawer] a[href^=http]').first().getAttribute('href').catch(() => null);
console.log('drawer link', href);
await page.keyboard.press('Escape');
await page.waitForTimeout(500);
await page.locator('button:has(svg.lucide-bell)').first().click().catch(() => {});
await snap('buy-08-notifications', { wait: 1500 });
await s.close();
