// Minimal static server for the spike (the real edge is nginx, see the spec).
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'

const types = { '.html': 'text/html', '.js': 'text/javascript' }
const root = new URL('.', import.meta.url).pathname

createServer(async (req, res) => {
  const path = normalize(req.url.split('?')[0]).replace(/^(\.\.[/\\])+/, '')
  const file = path === '/' ? 'public/index.html'
    : path.startsWith('/src/') ? path.slice(1) : join('public', path)
  try {
    const body = await readFile(join(root, file))
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404).end('not found')
  }
}).listen(5173, () => console.log('container on http://localhost:5173'))
