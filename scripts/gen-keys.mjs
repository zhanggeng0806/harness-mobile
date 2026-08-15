#!/usr/bin/env node
// 生成在线更新的 ECDSA P-256 密钥对：
//  - 公钥（X.509/SPKI, base64）→ 粘贴进 app 的 UpdateCrypto.kt（PINNED_PUBLIC_KEY_B64）
//  - 私钥（PKCS#8 PEM）→ 写入 scripts/update-key.private.pem，供 gen-manifest.mjs 签名（务必保密，勿提交）
import { generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));

const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const pubB64 = Buffer.from(publicKey.export({ type: 'spki', format: 'der' })).toString('base64');
const privPem = privateKey.export({ type: 'pkcs8', format: 'pem' });

writeFileSync(path.join(here, 'update-key.private.pem'), privPem, { mode: 0o600 });

console.log('PUBLIC_KEY_B64 (paste into UpdateCrypto.kt PINNED_PUBLIC_KEY_B64):');
console.log(pubB64);
console.log('');
console.log('Private key written to scripts/update-key.private.pem — KEEP SECRET, do not commit.');
