import { session } from './shot.mjs';
import fs from 'node:fs';
const st = JSON.parse(fs.readFileSync(new URL('./state.json', import.meta.url)));
const s = await session('cto@loaders.dev');
const { page, snap, go } = s;
const pick = async (label) => {
  await page.locator('[role=combobox]').filter({ hasText: /Wg zleceń|Wg proform|Bez grupowania|Wg zamówień/ }).first().click();
  await page.getByRole('option', { name: label }).click();
  await page.waitForTimeout(1000);
};
// материалы сметы и график
await go('/projects/2/materials');
await snap('mgr-20-estimate-materials', { wait: 2500 });
await go('/projects/2/scheduleDesign');
await snap('mgr-21-schedule', { wait: 2500 });
// планер
await go('/deliveries');
await pick('Bez grupowania');
await snap('mgr-01-planner');
await page.getByRole('button', { name: 'Dodaj pozycję' }).click();
await snap('mgr-03-add-position');
await page.keyboard.press('Escape');
await page.waitForTimeout(500);
// принятая позиция -> проформа
await page.locator('tr', { hasText: 'Hansgrohe Xuniva U umywalka' }).locator('input[type=checkbox]').check();
await snap('mgr-04-select-for-proforma');
await page.getByRole('button', { name: 'Utwórz proformę z zaznaczonych' }).click();
await snap('mgr-05-proforma-dialog', { wait: 1800 });
await page.getByRole('button', { name: 'Wystaw', exact: true }).click();
await page.waitForTimeout(800);
const confirm = page.locator('[role=alertdialog] button, [role=dialog] button').filter({ hasText: /^Wystaw/ });
if (await confirm.count()) await confirm.last().click();
await page.waitForTimeout(3000);
await snap('mgr-05b-proforma-issued', { wait: 1500 });
console.log('after issue url', page.url());
// согласование (staff)
await go('/projects/2/positionApprovals');
await snap('mgr-06-approvals-staff', { wait: 2000 });
// проформы
await go('/deliveries/proformas');
await snap('mgr-07-proformas', { wait: 1800 });
await go(`/deliveries/proformas/${st.p1.id}`);
await snap('mgr-08-proforma-detail', { wait: 2000 });
await go('/deliveries');
await pick('Wg proform');
for (const g of await page.locator('tr:has(button[aria-expanded=false])').all()) await g.locator('button').first().click().catch(() => {});
await snap('mgr-02-planner-by-proforma');
await page.setViewportSize({ width: 2560, height: 1100 });
await go('/deliveries');
await pick('Bez grupowania');
await snap('mgr-14-planner-wide');
await s.close();
