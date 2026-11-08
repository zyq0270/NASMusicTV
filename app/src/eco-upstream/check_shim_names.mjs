// 一次性校验：shim 里的具名导出在 three r160 里是否全部存在（避免 undefined 静默出错）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const shim = fs.readFileSync(path.join(HERE, 'three-shim.mjs'), 'utf8');
const names = [...shim.matchAll(/^export var (\w+) =/gm)].map((m) => m[1]);
const THREE = await import('./node_modules/three/build/three.module.js');
const missing = names.filter((n) => THREE[n] === undefined);
console.log('names', names.length);
console.log('missing', JSON.stringify(missing));
if (missing.length) process.exit(1);
