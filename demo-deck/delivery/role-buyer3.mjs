import { session } from './shot.mjs';
const s = await session('buyer@loaders.dev');
const { page, snap, go } = s;
const log = (...a) => console.log(...a);
const pickDay = async (day) => {
  await page.getByLabel('Planowana data dostawy').first().click();
  await page.waitForTimeout(500);
  await page.getByRole('button', { name: String(day), exact: true }).last().click().catch(async () => {
    await page.locator('[role=gridcell] button, td button').filter({ hasText: new RegExp(`^${day}$`) }).first().click();
  });
  await page.waitForTimeout(500);
};
const openForm = async (orderLabel) => {
  await page.getByRole('button', { name: 'Dodaj przesyłkę' }).click();
  await page.waitForTimeout(1000);
  await page.locator('[data-testid=shipment-form-dialog] button[role=combobox]').first().click();
  await page.getByPlaceholder('Szukaj...').fill('Jana Kazimierza');
  await page.waitForTimeout(1200);
  await page.getByRole('option', { name: /Jana Kazimierza/ }).first().click();
  await page.waitForTimeout(800);
  if (orderLabel) {
    const order = page.locator('[data-testid=shipment-order]');
    const ords = await order.locator('option').allInnerTexts();
    await order.selectOption({ label: ords.find((o) => o.includes(orderLabel)) });
    await page.waitForTimeout(800);
  }
};

await go('/deliveries/shipments');
// отправка 2: клиент покупает сам (без заказа), InPost
await openForm(null);
await page.locator('[data-testid^=shipment-position-row]', { hasText: 'Porta' }).locator('input[type=checkbox]').check();
await page.locator('[data-testid=shipment-carrier]').selectOption('INPOST');
await page.locator('[data-testid=shipment-tracking-number]').fill('620451118830442700012345');
await pickDay(27);
await page.locator('[data-testid=shipment-note]').fill('Ościeżnice Porta — klient zamówił sam, odbiór na budowie');
await snap('buy-07d-client-own-shipment', { wait: 800 });
await page.locator('[data-testid=shipment-submit]').click();
await page.waitForTimeout(2200);
await snap('buy-06-shipments', { wait: 1000 });
// карточка отправки со ссылкой перевозчика
await page.locator('tr', { hasText: 'PL9021188740' }).locator('button:has(svg.lucide-eye), button[aria-label*="zczeg" i]').first().click().catch(async () => {
  await page.locator('tr', { hasText: 'PL9021188740' }).getByRole('button').last().click();
});
await snap('buy-09-shipment-drawer-link', { wait: 1500 });
await page.keyboard.press('Escape');
await page.locator('button:has(svg.lucide-bell)').first().click().catch(() => {});
await snap('buy-08-notifications', { wait: 1500 });
await s.close();
