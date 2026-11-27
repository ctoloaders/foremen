import { psql, ok, login } from './lib.mjs';
// BUYER user
const exists = psql(`select count(*) from users where email='buyer@loaders.dev'`);
if (exists === '0') {
  console.log(psql(`INSERT INTO users (name, email, phone, role_id, active, locale, status, password_hash, created_date, created_by)
   SELECT 'Jakub Mazur', 'buyer@loaders.dev', '+48 601 220 118', r.id, true, 'pl', 'ACTIVE', (select password_hash from users where email='oleg@loaders.dev'), NOW(), 'demo'
   FROM roles r WHERE r.code='BUYER' RETURNING id;`));
}
console.log(psql(`select id,name,locale,role_id from users where email in ('buyer@loaders.dev','oleg@loaders.dev','piotr@loaders.dev')`));
const admin = await login('cto+1@loaders.dev');
const r = await ok('GET', '/api/company-profile', admin).catch((e) => String(e));
console.log(JSON.stringify(r).slice(0, 300));
