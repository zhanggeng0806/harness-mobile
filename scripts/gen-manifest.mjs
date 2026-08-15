#!/usr/bin/env node
// 给定快照文件与下载 URL，生成 manifest.json（url/sha256/size/signature）。
// 签名覆盖 "sha256=" + sha256hex（与 app 内 UpdateCrypto.verifySnapshotSha 一致）。
// 用法：node gen-manifest.mjs <snapshot.tar.gz> <https://host/snapshot.tar.gz> > manifest.json
import { createHash, sign, createPrivateKey } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const [file, url] = process.argv.slice(2);
if (!file || !url) {
  console.error('用法: node gen-manifest.mjs <snapshot.tar.gz> <url>');
  process.exit(1);
}

const data = readFileSync(file);
const sha256 = createHash('sha256').update(data).digest('hex');

const keyPem = readFileSync(path.join(here, 'update-key.private.pem'), 'utf8');
const key = createPrivateKey(keyPem);
const signature = sign('sha256', Buffer.from('sha256=' + sha256), { key, dsaEncoding: 'der' }).toString('base64');

console.log(JSON.stringify({ url, sha256, size: data.length, signature }, null, 2));
