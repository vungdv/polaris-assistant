#!/usr/bin/env node

/**
 * BPMN Diagram Renderer.
 *
 * Renders .bpmn files (BPMN 2.0 XML, editable in Camunda Modeler) to a PNG
 * image alongside them, via the `bpmn-to-image` CLI (bpmn-io).
 *
 * Supports:
 * 1. Claude Code PostToolUse hook (Write/Edit) - reads hook JSON from stdin
 * 2. CLI usage on demand - `node scripts/render-bpmn-diagram.mjs <file.bpmn> ...`
 *    (e.g. after pulling .bpmn files from a remote branch/repo)
 * 3. `--all [dir]` - render every .bpmn file under a directory
 *    (default: docs/business/bpmn)
 */

import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';

const DEFAULT_DIR = 'docs/business/bpmn';

function findSystemChrome() {
  if (process.env.PUPPETEER_EXECUTABLE_PATH) return process.env.PUPPETEER_EXECUTABLE_PATH;

  const candidates = {
    darwin: ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'],
    linux: ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'],
  }[process.platform] || [];

  return candidates.find((p) => fs.existsSync(p));
}

function renderOne(filePath) {
  if (!filePath.endsWith('.bpmn')) {
    return { filePath, skipped: true };
  }
  if (!fs.existsSync(filePath)) {
    return { filePath, ok: false, error: 'file not found' };
  }

  const outPath = filePath.replace(/\.bpmn$/, '.png');
  const chrome = findSystemChrome();
  const env = chrome ? { ...process.env, PUPPETEER_EXECUTABLE_PATH: chrome } : process.env;

  try {
    execFileSync('npx', ['--yes', 'bpmn-to-image', `${filePath}:${outPath}`], {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { filePath, ok: true, outPath };
  } catch (err) {
    const detail = err.stderr ? err.stderr.toString() : err.message;
    return { filePath, ok: false, error: detail };
  }
}

function findAllBpmnFiles(dir) {
  const results = [];
  const walk = (d) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && entry.name.endsWith('.bpmn')) results.push(full);
    }
  };
  if (fs.existsSync(dir)) walk(dir);
  return results;
}

async function handleHookMode() {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;

  let payload;
  try {
    payload = JSON.parse(input || '{}');
  } catch {
    return; // malformed input, don't block the agent
  }

  const filePath = payload?.tool_response?.filePath || payload?.tool_input?.file_path;
  if (!filePath || !filePath.endsWith('.bpmn')) return;

  const result = renderOne(filePath);
  if (result.ok) {
    console.log(JSON.stringify({ systemMessage: `Rendered ${path.basename(result.outPath)}` }));
  } else if (!result.skipped) {
    // Never block the agent on a rendering failure - just surface it.
    console.error(`[render-bpmn-diagram] failed to render ${filePath}: ${result.error}`);
  }
}

function handleCliMode(args) {
  let files;
  if (args[0] === '--all') {
    const dir = args[1] || DEFAULT_DIR;
    files = findAllBpmnFiles(dir);
    if (files.length === 0) {
      console.error(`No .bpmn files found under ${dir}`);
      process.exit(1);
    }
  } else {
    files = args;
  }

  let failed = 0;
  for (const f of files) {
    const result = renderOne(f);
    if (result.skipped) {
      console.error(`Skipping (not .bpmn): ${f}`);
      continue;
    }
    if (result.ok) {
      console.log(`Rendered ${result.outPath}`);
    } else {
      failed += 1;
      console.error(`Failed to render ${f}: ${result.error}`);
    }
  }
  if (failed > 0) process.exit(1);
}

async function main() {
  const args = process.argv.slice(2);
  const isHookMode = args.includes('--hook') || (!process.stdin.isTTY && args.length === 0);

  if (isHookMode) {
    await handleHookMode();
    return;
  }

  if (args.length === 0) {
    console.error('Usage: render-bpmn-diagram.mjs <file.bpmn> [...] | --all [dir] | --hook');
    process.exit(1);
  }

  handleCliMode(args);
}

main().catch((err) => {
  if (process.argv.includes('--hook')) {
    console.error('[render-bpmn-diagram] hook error:', err.message);
  } else {
    console.error('Unexpected error:', err);
    process.exit(1);
  }
});
