import { session } from './shot.mjs';
const s = await session('buyer@loaders.dev');
const { page, snap, go } = s;
const log = (...a) => console.log(...a);

// --- 1. Доска закупок
await go('/deliveries');
await page.getByText('Tablica zakupowca').click();
await page.locator('[role=combobox]').filter({ hasText: 'Moje' }).first().click();
await page.getByRole('option', { name: 'Wszyscy' }).click();
await page.waitForTimeout(1500);
await snap('buy-01-kanban');

// --- 2. Новый внешний заказ: проект выбираем в списке
await go('/deliveries/external-orders');
await snap('buy-02-orders-list', { wait: 1500 });
await page.getByRole('button', { name: 'Nowe zamówienie' }).click();
await page.waitForTimeout(1200);
await page.locator('button[role=combobox]').first().click();
await page.getByPlaceholder('Szukaj...').fill('Jana Kazimierza');
await page.waitForTimeout(1200);
await snap('buy-02b-project-picker', { wait: 500 });
await page.getByRole('option', { name: /Jana Kazimierza/ }).first().click();
await page.waitForTimeout(600);
await page.locator('button[role=combobox]').nth(1).click();
await page.getByRole('option', { name: /Leroy Merlin/ }).click();
await page.getByLabel('Numer zamówienia u sprzedawcy').fill('LM-2026-119902');
await page.getByLabel('Link do zamówienia').fill('https://www.leroymerlin.pl/zamowienie/LM-2026-119902');
await page.getByLabel('Koszt dostawy netto').fill('95');
await page.getByLabel('Uwagi').fill('Wanna Roca Savai i baterie Grohe Start, dostawa kurierska na budowę');
const sel = page.locator('select').last();
const opts = await sel.locator('option').allInnerTexts();
for (const key of ['Roca Savai', 'Grohe Start']) {
  const label = opts.find((o) => o.includes(key));
  if (!label) { log('no option', key); continue; }
  await sel.selectOption({ label });
  await page.getByRole('button', { name: 'Dodaj pozycję' }).click();
  await page.waitForTimeout(600);
}
await snap('buy-03-new-order-filled', { wait: 1000 });
await page.getByRole('button', { name: 'Zapisz' }).click();
await page.waitForTimeout(2200);
await snap('buy-04-order-saved', { wait: 800 });
await page.getByRole('button', { name: 'Wyślij do płatności' }).first().click();
await page.waitForTimeout(700);
await snap('buy-05-send-confirm', { wait: 500 });
await page.locator('[role=alertdialog] button, [role=dialog] button').filter({ hasText: 'Wyślij do płatności' }).last().click();
await page.waitForTimeout(2200);
await snap('buy-05b-order-sent', { wait: 500 });

// --- 3. Отправка: Castorama (оплачен финансистом) -> GLS
await go('/deliveries/shipments');
await snap('buy-06-shipments', { wait: 1500 });
await page.getByRole('button', { name: 'Dodaj przesyłkę' }).click();
await page.waitForTimeout(1000);
await page.locator('[data-testid=shipment-form-dialog] button[role=combobox]').first().click();
await page.getByPlaceholder('Szukaj...').fill('Jana Kazimierza');
await page.waitForTimeout(1200);
await page.getByRole('option', { name: /Jana Kazimierza/ }).first().click();
await page.waitForTimeout(800);
const order = page.locator('[data-testid=shipment-order]');
const ords = await order.locator('option').allInnerTexts();
log('orders', ords);
await order.selectOption({ label: ords.find((o) => o.includes('CAS-W-77120')) ?? ords[1] });
await page.waitForTimeout(1000);
const checks = page.locator('[data-testid^=shipment-position-]:not([data-testid*=row]):not([data-testid*=empty]) input, input[type=checkbox][data-testid^=shipment-position-]');
log('checkboxes', await checks.count());
await checks.first().check().catch((e) => log('check', String(e).slice(0, 80)));
await page.locator('[data-testid=shipment-carrier]').selectOption('GLS');
await page.locator('[data-testid=shipment-tracking-number]').fill('PL9021188740');
await snap('buy-07-new-shipment-form', { wait: 800 });
// дата
const dateBtn = page.getByLabel('Planowana data dostawy').first();
await dateBtn.click().catch((e) => log('date', String(e).slice(0, 80)));
await snap('buy-07b-date', { wait: 600 });
await s.close();
