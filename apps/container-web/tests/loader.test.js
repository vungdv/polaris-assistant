import { describe, it, expect, vi, beforeEach } from 'vitest'
import { loadRemote } from '../src/loader.js'

let slot
beforeEach(() => {
  document.body.innerHTML = '<div id="slot"></div>'
  slot = document.getElementById('slot')
  vi.restoreAllMocks()
})

const opts = (extra = {}) => ({ name: 'assistant', url: 'http://x/a.js', tag: 'polaris-assistant', slot, ...extra })

describe('loadRemote', () => {
  it('imports the configured url and mounts the element', async () => {
    const importFn = vi.fn().mockResolvedValue({})
    expect(await loadRemote(opts(), importFn)).toBe(true)
    expect(importFn).toHaveBeenCalledWith('http://x/a.js')
    expect(slot.querySelector('polaris-assistant')).not.toBeNull()
  })

  it('shows fallback and logs one structured error on import failure (AC-4)', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const importFn = vi.fn().mockRejectedValue(new Error('404'))
    expect(await loadRemote(opts(), importFn)).toBe(false)
    expect(slot.textContent).toContain('unavailable')
    expect(err).toHaveBeenCalledTimes(1)
    expect(JSON.parse(err.mock.calls[0][0])).toEqual({ level: 'error', remote: 'assistant', reason: '404', url: 'http://x/a.js' })
  })

  it('falls back on timeout (AC-4)', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const importFn = () => new Promise(() => {})
    expect(await loadRemote(opts({ timeoutMs: 20 }), importFn)).toBe(false)
    expect(slot.textContent).toContain('unavailable')
  })

  it('uses whatever url the runtime config provides (AC-7)', async () => {
    const importFn = vi.fn().mockResolvedValue({})
    await loadRemote(opts({ url: 'http://other/b.js' }), importFn)
    expect(importFn).toHaveBeenCalledWith('http://other/b.js')
  })
})
