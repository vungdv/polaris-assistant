# Runbook: Qwen on local Ollama

Dev-only setup for the `qwen` model provider (plan: `docs/development/plan/qwen-model-client.md`). macOS only.

- Install Ollama, start it and pull the model: `scripts/ollama.sh install` (model from `OLLAMA_MODEL`, default `qwen3:4b`). Other subcommands: `clean` (remove the model), `purge` (uninstall Ollama and delete `~/.ollama`).
- Point the assistant at it: `POLARIS_AI_PROVIDER=qwen QWEN_BASE_URL=http://localhost:11434/v1`. `QWEN_API_KEY` may stay blank.
- Resilience: the `qwen` circuit breaker opens at 50 % failures over the last 20 calls (min. 10) for 30 s; while open, turns fail at once with `503` and `Retry-After`. Retries: `QWEN_RETRY_MAX_ATTEMPTS` (default 2), `QWEN_RETRY_WAIT` (default 500ms). Breaker state: `/actuator/health` component `circuitBreakers.qwen`.
- If Ollama is down or the model is not pulled, calls fail as `503`; run `scripts/ollama.sh install` (safe to re-run), then wait for the breaker to half-open (30 s) or restart the assistant.
