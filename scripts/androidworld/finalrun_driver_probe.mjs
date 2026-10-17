// Readiness probe for an owned FinalRun driver: one local capture, no models, credentials or installs.
// Usage: node finalrun_driver_probe.mjs <FinalRun worker.mjs path> <port>
import { createRequire } from 'node:module';

console.log = () => {}; console.error = () => {};
const require = createRequire(process.argv[2]);
const { GrpcDriverClient } = require('@finalrun/device-node');
const port = Number(process.argv[3]);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('invalid_owned_port');
const client = new GrpcDriverClient();
const timer = setTimeout(() => { client.close(); process.exit(1); }, 15000);
try {
  if (!await client.connect('127.0.0.1', port)) throw new Error('driver_not_ready');
  const capture = await client.getScreenshotAndHierarchy(undefined, { timeoutMs: 10000 });
  if (capture.success !== true) throw new Error('capture_failed');
  process.stdout.write(JSON.stringify({ connected: true, hierarchy_bytes: Buffer.byteLength(capture.hierarchy ?? ''),
    screenshot_bytes: Buffer.byteLength(capture.screenshot ?? '') }));
} catch { process.exitCode = 1; }
finally { clearTimeout(timer); client.close(); }
