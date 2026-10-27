<script setup>
import { onMounted, getCurrentInstance } from 'vue'

const instance = getCurrentInstance()

// Outbound contract: DOM event, bubbles + composed so it crosses the shadow boundary.
onMounted(() => {
  instance.proxy.$el.getRootNode().host?.dispatchEvent(
    new CustomEvent('polaris:assistant:ready', { bubbles: true, composed: true }),
  )
})
</script>

<template>
  <section class="card">
    <h2>Hello from Assistant</h2>
  </section>
</template>

<style>
.card { font-family: sans-serif; padding: 1rem; border: 1px solid #4a90d9; border-radius: 8px; }
h2 { margin: 0; color: #4a90d9; }
</style>
