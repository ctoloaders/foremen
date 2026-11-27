import { session } from './shot.mjs';
const [, , email, ...paths] = process.argv;
const s = await session(email, { mobile: process.env.MOBILE === '1' });
let i = 0;
for (const p of paths) {
  await s.go(p);
  await s.snap(`explore-${email.split('@')[0]}-${i++}`, { wait: 1800 });
  console.log(p, '->', s.page.url());
}
await s.close();
