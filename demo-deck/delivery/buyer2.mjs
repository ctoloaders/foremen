import { session } from './shot.mjs';
const s = await session('buyer@loaders.dev');
const { page, snap, go } = s;
await go('/deliveries/external-orders/new?project=2');
await page.waitForTimeout(1500);
await page.locator('button[role=combobox]').nth(1).click();
await page.getByRole('option', { name: /Leroy Merlin/ }).click();
await page.getByLabel('Numer zamówienia u sprzedawcy').fill('LM-2026-119902');
await page.getByLabel('Link do zamówienia').fill('https://www.leroymerlin.pl/zamowienie/LM-2026-119902');
await page.getByLabel('Koszt dostawy netto').fill('95');
await page.getByLabel('Uwagi').fill('Wanna Roca Savai i baterie Grohe Start, dostawa kurierska na budowę');
const sel = page.locator('select').last();
const opts = await sel.locator('option').allInnerTexts();
console.log(opts);
for (const key of ['Roca Savai', 'Grohe Start']) {
  const label = opts.find((o) => o.includes(key));
  if (!label) continue;
  await sel.selectOption({ label });
  await page.getByRole('button', { name: 'Dodaj pozycję' }).click();
  await page.waitForTimeout(600);
}
await snap('buy-03-new-order-filled', { wait: 1000 });
await page.getByLabel('Planowana dostawa').fill('2026-11-24').catch(()=>{});
await page.getByRole('button', { name: 'Zapisz' }).click();
await page.waitForTimeout(2000);
await snap('buy-04-order-saved');
console.log((await page.getByRole('button').allInnerTexts()).join(' | '));
await s.close();
