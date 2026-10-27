const DEFAULT_TIMEOUT_MS = 5000

const logError = (remote, reason, url) =>
  console.error(JSON.stringify({ level: 'error', remote, reason, url }))

/**
 * Loads a remote ES module, then appends its custom element into `slot`.
 * Never throws: on failure or timeout it renders a fallback and logs one JSON line.
 * `importFn` is the network boundary and is injectable for tests.
 */
export async function loadRemote(
  { name, url, tag, slot, timeoutMs = DEFAULT_TIMEOUT_MS },
  importFn = (u) => import(/* @vite-ignore */ u),
) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('timeout')), timeoutMs)
  })
  try {
    await Promise.race([importFn(url), timeout])
    slot.replaceChildren(document.createElement(tag))
    return true
  } catch (err) {
    logError(name, err.message, url)
    const p = document.createElement('p')
    p.textContent = `${name} is currently unavailable.`
    slot.replaceChildren(p)
    return false
  } finally {
    clearTimeout(timer)
  }
}
