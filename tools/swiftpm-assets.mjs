// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Prefetch pinned archives through the GitHub API when release-page downloads fail.
// SwiftPM still checks the archive against its unchanged package manifest.
// Cache layout: swiftlang/swift-package-manager Sources/Workspace/Workspace+BinaryArtifacts.swift.
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMain } from './lib.mjs';

export function artifactURL(artifact) {
  return `https://github.com/${artifact.repository}/releases/download/${artifact.version}/${artifact.filename}`;
}

// SwiftPM's C99 mangling for the ASCII-only URLs admitted below. No leading digit is possible.
export function cacheKey(url) {
  if (!/^https:\/\/github\.com\/[\x21-\x7e]+$/.test(url)) throw new Error('Unsupported artifact URL');
  return url.replace(/[^A-Za-z0-9_]/g, '_');
}

export function artifactsFor(registry, resolved, platform) {
  if (registry.version !== 1 || !['ios', 'macos'].includes(platform)) throw new Error('Unsupported asset registry/platform');
  const seen = new Set();
  for (const artifact of registry.artifacts) {
    const pin = resolved.pins.find((value) => value.identity === artifact.identity);
    const repository = pin?.location?.replace(/^https:\/\/github\.com\//, '').replace(/\.git$/, '');
    if (!pin || repository !== artifact.repository || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)
        || artifact.version !== pin.state.version || !/^\d+(\.\d+){2}$/.test(artifact.version)
        || !/^[A-Za-z0-9_-][A-Za-z0-9_.-]*\.zip$/.test(artifact.filename)
        || basename(artifact.filename) !== artifact.filename || !/^[a-f0-9]{64}$/.test(artifact.sha256)
        || seen.has(artifact.identity) || !artifact.platforms?.every((value) => ['ios', 'macos'].includes(value))) {
      throw new Error(`Invalid or stale pinned artifact: ${artifact.identity}`);
    }
    seen.add(artifact.identity);
  }
  return registry.artifacts.filter((value) => value.platforms.includes(platform));
}

export function checkManifest(artifact, manifest) {
  if (![artifact.sha256, artifact.filename, artifact.version].every((value) => manifest.includes(value))) {
    throw new Error(`Archive does not match pinned Package.swift: ${artifact.identity}`);
  }
}

async function checksum(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

export async function cacheArtifact(artifact, directory, download) {
  const folder = join(directory, 'artifacts');
  const destination = join(folder, cacheKey(artifactURL(artifact)));
  if (existsSync(destination) && await checksum(destination) === artifact.sha256) return destination;
  const downloaded = await download();
  if (await checksum(downloaded) !== artifact.sha256) throw new Error(`SHA-256 mismatch: ${artifact.identity}`);
  mkdirSync(folder, { recursive: true });
  const stage = destination + '.pending-' + randomUUID();
  try {
    copyFileSync(downloaded, stage);
    renameSync(stage, destination);
  } finally { rmSync(stage, { force: true }); }
  return destination;
}

if (isMain(import.meta.url)) {
  const option = (name) => process.argv[process.argv.indexOf(name) + 1];
  if (!process.argv.includes('--cache') || !process.argv.includes('--platform')) throw new Error('Use --cache <directory> --platform ios|macos');
  const directory = resolve(option('--cache'));
  const root = fileURLToPath(new URL('..', import.meta.url));
  const registry = JSON.parse(readFileSync(join(root, 'tools/swiftpm-assets.json'), 'utf8'));
  const resolved = JSON.parse(readFileSync(join(root, 'ios/Package.resolved'), 'utf8'));
  for (const artifact of artifactsFor(registry, resolved, option('--platform'))) {
    const pin = resolved.pins.find((value) => value.identity === artifact.identity);
    const manifest = execFileSync('gh', ['api', '-H', 'Accept: application/vnd.github.raw+json',
      `repos/${artifact.repository}/contents/Package.swift?ref=${pin.state.revision}`], { encoding: 'utf8', timeout: 60000 });
    checkManifest(artifact, manifest);
    const temporary = mkdtempSync(join(tmpdir(), 'bipe-swiftpm-'));
    try {
      await cacheArtifact(artifact, directory, async () => {
        for (let attempt = 0; ; attempt += 1) {
          try {
            execFileSync('gh', ['release', 'download', artifact.version, '--repo', artifact.repository,
              '--pattern', artifact.filename, '--dir', temporary, '--clobber'], { stdio: 'pipe', timeout: 180000 });
            return join(temporary, artifact.filename);
          } catch (error) {
            if (attempt === 2) throw error;
            await new Promise((done) => setTimeout(done, (attempt + 1) * 1000));
          }
        }
      });
      process.stdout.write(`Verified SwiftPM archive: ${artifact.identity} ${artifact.version}\n`);
    } finally { rmSync(temporary, { recursive: true, force: true }); }
  }
}
