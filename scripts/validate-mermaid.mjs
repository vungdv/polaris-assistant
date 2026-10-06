#!/usr/bin/env node

/**
 * Mermaid Diagram Validator for Markdown files.
 *
 * Supports:
 * 1. Antigravity lifecycle hook (PreToolUse on write_to_file and replace_file_content)
 * 2. Git pre-commit hook (validating staged markdown files via --staged)
 * 3. CLI usage (validating specific files or --all markdown files in the project)
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import url from 'url';
import { execSync } from 'child_process';

/**
 * Locate or install mermaid and dompurify in user cache if not present.
 */
function findModulePath(modName) {
  // 1. Direct Node resolution
  try {
    const resolved = import.meta.resolve ? import.meta.resolve(modName) : null;
    if (resolved) return url.fileURLToPath(resolved);
  } catch (e) {}

  // 2. Local node_modules up the directory tree
  let curr = process.cwd();
  while (curr !== path.parse(curr).root) {
    const candidate = path.join(curr, 'node_modules', modName);
    if (fs.existsSync(candidate)) return candidate;
    curr = path.dirname(curr);
  }

  // 3. User cache directory (~/.cache/antigravity-mermaid)
  const userCache = path.join(os.homedir(), '.cache', 'antigravity-mermaid', 'node_modules', modName);
  if (fs.existsSync(userCache)) return userCache;

  // 4. npm npx cache (~/.npm/_npx)
  const npxDir = path.join(os.homedir(), '.npm', '_npx');
  if (fs.existsSync(npxDir)) {
    try {
      const subdirs = fs.readdirSync(npxDir);
      for (const sub of subdirs) {
        const candidate = path.join(npxDir, sub, 'node_modules', modName);
        if (fs.existsSync(candidate)) return candidate;
      }
    } catch (e) {}
  }

  // 5. Global npm root
  try {
    const globalRoot = execSync('npm root -g', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    const candidate = path.join(globalRoot, modName);
    if (fs.existsSync(candidate)) return candidate;
  } catch (e) {}

  return null;
}

/**
 * Ensure dependencies are present, auto-installing to ~/.cache/antigravity-mermaid if missing.
 */
async function loadDependencies() {
  let mermaidPath = findModulePath('mermaid');
  let dompurifyPath = findModulePath('dompurify');

  if (!mermaidPath || !dompurifyPath) {
    const cacheDir = path.join(os.homedir(), '.cache', 'antigravity-mermaid');
    fs.mkdirSync(cacheDir, { recursive: true });
    try {
      execSync('npm install --prefix "' + cacheDir + '" --no-save --no-audit --no-fund --silent mermaid@^11 dompurify@^3', {
        stdio: 'ignore'
      });
      mermaidPath = path.join(cacheDir, 'node_modules', 'mermaid');
      dompurifyPath = path.join(cacheDir, 'node_modules', 'dompurify');
    } catch (err) {
      throw new Error('Failed to install mermaid validator dependencies: ' + err.message);
    }
  }

  // Load DOMPurify and apply headless node patch
  let dompurifyMod;
  const dompurifyEntry = path.join(dompurifyPath, 'dist', 'purify.es.mjs');
  if (fs.existsSync(dompurifyEntry)) {
    dompurifyMod = (await import(url.pathToFileURL(dompurifyEntry).href)).default;
  } else {
    dompurifyMod = (await import(url.pathToFileURL(dompurifyPath).href)).default;
  }
  if (dompurifyMod) {
    dompurifyMod.addHook = dompurifyMod.addHook || (() => {});
    dompurifyMod.sanitize = dompurifyMod.sanitize || ((s) => s);
  }

  // Load Mermaid core
  let mermaidMod;
  const mermaidEntry = path.join(mermaidPath, 'dist', 'mermaid.core.mjs');
  if (fs.existsSync(mermaidEntry)) {
    mermaidMod = (await import(url.pathToFileURL(mermaidEntry).href)).default;
  } else {
    mermaidMod = (await import(url.pathToFileURL(mermaidPath).href)).default;
  }

  mermaidMod.initialize({
    startOnLoad: false,
    securityLevel: 'loose'
  });

  return mermaidMod;
}

/**
 * Extract all ```mermaid ... ``` code blocks from markdown.
 */
function extractMermaidDiagrams(markdown) {
  const regex = /```mermaid[^\n]*\r?\n([\s\S]*?)```/g;
  const diagrams = [];
  let match;
  while ((match = regex.exec(markdown)) !== null) {
    const code = match[1].trim();
    const preText = markdown.slice(0, match.index);
    const lineNumber = preText.split('\n').length;
    diagrams.push({
      index: diagrams.length + 1,
      lineNumber,
      code
    });
  }
  return diagrams;
}

/**
 * Validate all diagrams in given markdown text.
 */
async function validateMarkdownContent(content, filePath, mermaid) {
  const diagrams = extractMermaidDiagrams(content);
  if (diagrams.length === 0) {
    return { ok: true, count: 0, errors: [] };
  }

  const errors = [];
  for (const diag of diagrams) {
    try {
      await mermaid.parse(diag.code);
    } catch (err) {
      errors.push({
        filePath,
        diagramIndex: diag.index,
        lineNumber: diag.lineNumber,
        message: err.message
      });
    }
  }

  return {
    ok: errors.length === 0,
    count: diagrams.length,
    errors
  };
}

/**
 * Simulate file content after replace_file_content tool call.
 */
function simulateReplacement(original, args) {
  const { StartLine, EndLine, TargetContent, ReplacementContent, AllowMultiple } = args;
  if (!original) return ReplacementContent || '';

  if (StartLine && EndLine && StartLine <= EndLine) {
    const lines = original.split('\n');
    if (StartLine <= lines.length) {
      const before = lines.slice(0, StartLine - 1).join('\n');
      const chunk = lines.slice(StartLine - 1, EndLine).join('\n');
      const after = lines.slice(EndLine).join('\n');

      if (chunk.includes(TargetContent)) {
        const replacedChunk = chunk.replace(TargetContent, ReplacementContent);
        return (before ? before + '\n' : '') + replacedChunk + (after ? '\n' + after : '');
      }
    }
  }

  if (AllowMultiple) {
    return original.replaceAll(TargetContent, ReplacementContent);
  }
  return original.replace(TargetContent, ReplacementContent);
}

/**
 * Antigravity PreToolUse hook handler.
 */
async function handleHookMode(mermaid) {
  // Read JSON from stdin
  const input = await new Promise((resolve) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => (data += chunk));
    process.stdin.on('end', () => resolve(data.trim()));
  });

  if (!input) {
    console.log(JSON.stringify({ decision: 'allow' }));
    return;
  }

  let payload;
  try {
    payload = JSON.parse(input);
  } catch (e) {
    // Non-JSON input, allow tool execution
    console.log(JSON.stringify({ decision: 'allow' }));
    return;
  }

  const toolCall = payload.toolCall;
  if (!toolCall || !toolCall.args) {
    console.log(JSON.stringify({ decision: 'allow' }));
    return;
  }

  const targetFile = toolCall.args.TargetFile || '';
  if (!targetFile.endsWith('.md') && !targetFile.endsWith('.markdown')) {
    // Not a markdown file, allow immediately
    console.log(JSON.stringify({ decision: 'allow' }));
    return;
  }

  let contentToValidate = '';
  if (toolCall.name === 'write_to_file') {
    contentToValidate = toolCall.args.CodeContent || '';
  } else if (toolCall.name === 'replace_file_content') {
    let original = '';
    if (fs.existsSync(targetFile)) {
      original = fs.readFileSync(targetFile, 'utf8');
    }
    contentToValidate = simulateReplacement(original, toolCall.args);
  } else {
    console.log(JSON.stringify({ decision: 'allow' }));
    return;
  }

  const result = await validateMarkdownContent(contentToValidate, targetFile, mermaid);
  if (result.ok) {
    console.log(JSON.stringify({ decision: 'allow' }));
  } else {
    const err = result.errors[0];
    const fileName = path.basename(targetFile);
    const reason = `Mermaid diagram validation failed for ${fileName} (diagram #${err.diagramIndex} starting near line ${err.lineNumber}):\n\n${err.message}\n\nPlease fix the diagram syntax error before writing to the file.`;
    console.log(JSON.stringify({
      decision: 'deny',
      reason
    }));
  }
}

/**
 * Recursively find all markdown files.
 */
function findMarkdownFiles(dir) {
  let files = [];
  try {
    for (const item of fs.readdirSync(dir)) {
      if (item === 'node_modules' || item === '.git' || item === 'target' || item === 'dist' || item === '.idea') {
        continue;
      }
      const fullPath = path.join(dir, item);
      const stat = fs.statSync(fullPath);
      if (stat.isDirectory()) {
        files = files.concat(findMarkdownFiles(fullPath));
      } else if (item.endsWith('.md') || item.endsWith('.markdown')) {
        files.push(fullPath);
      }
    }
  } catch (e) {}
  return files;
}

/**
 * CLI / Pre-commit handler.
 */
async function handleCliMode(mermaid, args) {
  let filesToScan = [];

  if (args.includes('--install-git-hook')) {
    let hooksDir;
    try {
      hooksDir = execSync('git rev-parse --git-path hooks', { encoding: 'utf8' }).trim();
    } catch (e) {
      console.error('Could not determine git hooks directory:', e.message);
      process.exit(1);
    }
    const hookPath = path.resolve(hooksDir, 'pre-commit');
    const hookContent = `#!/bin/sh
GIT_ROOT="$(git rev-parse --show-toplevel)"
if [ -f "$GIT_ROOT/src/java/03-Polaris/scripts/validate-mermaid.mjs" ]; then
  node "$GIT_ROOT/src/java/03-Polaris/scripts/validate-mermaid.mjs" --staged
elif [ -f "$GIT_ROOT/scripts/validate-mermaid.mjs" ]; then
  node "$GIT_ROOT/scripts/validate-mermaid.mjs" --staged
fi
`;
    fs.writeFileSync(hookPath, hookContent, { mode: 0o755 });
    console.log(`Successfully installed Git pre-commit hook at ${hookPath}`);
    return;
  }

  if (args.includes('--staged')) {
    try {
      const gitRoot = execSync('git rev-parse --show-toplevel', { encoding: 'utf8' }).trim();
      const output = execSync('git diff --cached --name-only --diff-filter=ACM', { encoding: 'utf8' });
      filesToScan = output
        .split('\n')
        .map((f) => f.trim())
        .filter((f) => f.endsWith('.md') || f.endsWith('.markdown'))
        .map((f) => (path.isAbsolute(f) ? f : path.resolve(gitRoot, f)))
        .filter((f) => fs.existsSync(f));
    } catch (e) {
      console.error('Failed to get staged git files:', e.message);
      process.exit(1);
    }
    if (filesToScan.length === 0) {
      console.log('No staged markdown files found to validate.');
      return;
    }
  } else if (args.includes('--all')) {
    filesToScan = findMarkdownFiles(process.cwd());
  } else {
    filesToScan = args.filter((arg) => !arg.startsWith('--') && (arg.endsWith('.md') || arg.endsWith('.markdown')));
    if (filesToScan.length === 0) {
      filesToScan = findMarkdownFiles(process.cwd());
    }
  }

  console.log(`Validating Mermaid diagrams in ${filesToScan.length} markdown file(s)...`);
  let totalDiagrams = 0;
  let allErrors = [];

  for (const file of filesToScan) {
    try {
      const content = fs.readFileSync(file, 'utf8');
      const result = await validateMarkdownContent(content, file, mermaid);
      totalDiagrams += result.count;
      if (!result.ok) {
        allErrors.push(...result.errors);
      }
    } catch (e) {
      allErrors.push({ filePath: file, diagramIndex: 0, lineNumber: 0, message: e.message });
    }
  }

  if (allErrors.length > 0) {
    console.error(`\nValidation FAILED! Found ${allErrors.length} Mermaid diagram error(s):`);
    for (const err of allErrors) {
      console.error(`\n[ERROR] ${err.filePath}:${err.lineNumber} (Diagram #${err.diagramIndex})`);
      console.error(err.message);
    }
    process.exit(1);
  } else {
    console.log(`Validation SUCCESS! Verified ${totalDiagrams} Mermaid diagram(s) across ${filesToScan.length} file(s). No syntax errors found.`);
  }
}

async function main() {
  const args = process.argv.slice(2);
  const isHookMode = args.includes('--hook') || (!process.stdin.isTTY && args.length === 0);

  let mermaid;
  try {
    mermaid = await loadDependencies();
  } catch (err) {
    if (isHookMode) {
      // In hook mode, if dependency loader fails, allow with warning to avoid breaking agent
      console.log(JSON.stringify({ decision: 'allow' }));
      return;
    }
    console.error(err.message);
    process.exit(1);
  }

  if (isHookMode) {
    await handleHookMode(mermaid);
  } else {
    await handleCliMode(mermaid, args);
  }
}

main().catch((err) => {
  if (process.argv.includes('--hook')) {
    console.log(JSON.stringify({ decision: 'allow' }));
  } else {
    console.error('Unexpected error:', err);
    process.exit(1);
  }
});
