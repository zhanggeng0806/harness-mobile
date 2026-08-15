#!/usr/bin/env node
// 快照更新服务器：serves /manifest.json 与 /<snapshot 文件名>。
// 用于 debug 构建在局域网/模拟器上测试在线更新（app debug 构建放行 http）。
// 生产应放在 HTTPS 后面（nginx/caddy 反代即可）。
//
// 用法：
//   node snapshot-server.mjs --manifest manifest.json --snapshot snapshot.tar.gz [--port 8899]
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);
const port = Number(args.port || 8899);
const manifestFile = args.manifest || 'manifest.json';
const snapshotFile = args.snapshot || 'snapshot.tar.gz';

if (!existsSync(manifestFile) || !existsSync(snapshotFile)) {
  console.error('需要 --manifest 与 --snapshot 两个文件均存在');
  process.exit(1);
}

const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'));
const snapshotName = path.basename(manifest.url);

const server = http.createServer((req, res) => {
  if (req.url === '/manifest.json') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(manifest));
    return;
  }
  if (req.url === '/' + snapshotName || req.url === '/' + path.basename(snapshotFile)) {
    res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
    res.end(readFileSync(snapshotFile));
    return;
  }
  res.writeHead(404);
  res.end('not found');
});

server.listen(port, '0.0.0.0', () => {
  console.log(`snapshot server on http://0.0.0.0:${port}  (manifest: /manifest.json, snapshot: /${snapshotName})`);
});
