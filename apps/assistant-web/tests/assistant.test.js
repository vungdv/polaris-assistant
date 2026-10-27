import { describe, it, expect, vi } from 'vitest'
import '../src/main.js'

const tick = () => new Promise((r) => setTimeout(r))

describe('<polaris-assistant>', () => {
  it('renders hello inside shadow DOM', async () => {
    const el = document.createElement('polaris-assistant')
    document.body.append(el)
    await tick()
    expect(el.shadowRoot.textContent).toContain('Hello from Assistant')
    el.remove()
  })

  it('can be imported twice without error (AC-5)', async () => {
    vi.resetModules()
    await expect(import('../src/main.js')).resolves.toBeDefined()
  })

  it('supports mount/unmount/mount (AC-5)', async () => {
    const el = document.createElement('polaris-assistant')
    document.body.append(el)
    await tick()
    el.remove()
    document.body.append(el)
    await tick()
    expect(el.shadowRoot.textContent).toContain('Hello from Assistant')
    el.remove()
  })

  it('dispatches ready once per mounted element, reaching a container listener (AC-6)', async () => {
    const onReady = vi.fn()
    document.body.addEventListener('polaris:assistant:ready', onReady)
    const el = document.createElement('polaris-assistant')
    document.body.append(el)
    await tick()
    expect(onReady).toHaveBeenCalledTimes(1)
    document.body.removeEventListener('polaris:assistant:ready', onReady)
    el.remove()
  })
})
