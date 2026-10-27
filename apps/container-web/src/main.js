import { loadRemote } from './loader.js'

const url = window.__POLARIS_CONFIG__?.remotes?.assistant
const slot = document.getElementById('assistant-slot')

// Remote -> container communication is DOM events only.
slot.addEventListener('polaris:assistant:ready', () => console.info('assistant ready'))

loadRemote({ name: 'assistant', url, tag: 'polaris-assistant', slot })
