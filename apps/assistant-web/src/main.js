import { defineCustomElement } from 'vue'
import Assistant from './Assistant.ce.vue'

export const PolarisAssistant = defineCustomElement(Assistant)

// Idempotent: re-importing the module must not throw on duplicate define.
if (!customElements.get('polaris-assistant')) {
  customElements.define('polaris-assistant', PolarisAssistant)
}
