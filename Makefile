.PHONY: seed-shoppers chat-scenarios pull build up up-dev-ports down validate-docs setup-hooks render-bpmn test test-rules test-perf test-concurrency e2e-fulfilment e2e-fulfilment gcx gcx-exec status playwright-ui playwright-close run run-assistant run-fulfilment verify-fulfilment-image clean polaris-sql kafka-topics kafka-tail kafka-cluster kafka-offsets kafka-groups kafka-leaders

run:
	mvn spring-boot:run -pl apps/polaris

run-assistant:
	mvn spring-boot:run -pl apps/polaris-assistant

run-fulfilment:
	mvn spring-boot:run -pl apps/polaris-fulfilment-emulator

verify-fulfilment-image:
	./scripts/verify-fulfilment-image.sh

pull: 
	docker compose pull
build:
	docker compose build
up:
	docker compose up -d --build
# Opt-in loopback publish of telemetry backends for host-side tooling
up-dev-ports:
	docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.dev-ports.yml up -d --build
down: 
	docker compose down
clean:
	docker compose down --volumes --remove-orphans
status:
	docker compose ps
restart-%:
	docker compose restart $*
test:
	mvn clean test
# promtool unit tests for the Prometheus recording/alerting rules (same image as the stack)
test-rules:
	docker run --rm -v $$(pwd)/docker/telemetry/prometheus:/prom:ro -w /prom/tests --entrypoint promtool prom/prometheus:v3.14.0 test rules $$(cd docker/telemetry/prometheus/tests && ls *.test.yml)
test-perf:
	docker run --network host --rm -i -v $$(pwd)/tests/perf:/scripts -w /scripts grafana/k6 run api-test.js
test-concurrency:
	docker run --network host --rm -i -v $$(pwd)/tests/perf:/scripts -w /scripts -e SKU=NG-CHARGER-02 grafana/k6 run order-concurrency-test.js
e2e-fulfilment:
	./tests/e2e/run-fulfilment.sh
seed-shoppers:
	docker run --rm -i --add-host id.polaris.local:host-gateway -v $$(pwd)/tests/e2e/k6:/scripts:ro -w /scripts \
	  -e KC_BASE -e REALM -e KC_ADMIN_USER -e KC_ADMIN_PASSWORD -e SHOPPER_COUNT -e SHOPPER_PREFIX -e SHOPPER_PASSWORD grafana/k6 run seed-shoppers.js
chat-scenarios:
	docker run --rm -i --add-host id.polaris.local:host-gateway --add-host host.docker.internal:host-gateway \
	  -v $$(pwd)/tests/e2e/k6:/scripts:ro -w /scripts \
	  -e API_BASE -e ASSISTANT_BASE -e KC_BASE -e REALM -e E2E_STAFF_USER -e E2E_STAFF_PASSWORD -e SKU -e SHOPPER_COUNT -e SHOPPER_PREFIX \
	  -e SHOPPER_PASSWORD -e SEARCH_QUERY -e CHAT_VUS -e CHAT_ITERATIONS -e CHAT_TIMEOUT -e THINK_TIME grafana/k6 run chat-scenarios.js
gcx: 
	docker exec -it gcx-cli sh
gcx-exec:
	docker exec gcx-cli gcx $(CMD)
agy-ls:
	ls -t ~/.gemini/antigravity-cli/brain | head -n 10
validate-docs:
	node scripts/validate-mermaid.mjs --all
setup-hooks:
	node scripts/validate-mermaid.mjs --install-git-hook
render-bpmn:
	node scripts/render-bpmn-diagram.mjs --all
render-bpmn-%:
	node scripts/render-bpmn-diagram.mjs docs/business/bpmn/$*.bpmn
polaris-sql:
	docker compose exec polaris-db psql -U polaris -d polaris
KAFKA_BIN := /opt/kafka/bin
KAFKA_BOOTSTRAP := kafka-1:9092,kafka-2:9092,kafka-3:9092
# Admin commands run inside a node; any running node works (NODE=2 if kafka-1 is stopped).
NODE ?= 1
KAFKA_EXEC = docker compose exec kafka-$(NODE)
kafka-topics:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-topics.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --describe --exclude-internal
kafka-tail:
	@test -n "$(TOPIC)" || { echo "usage: make kafka-tail TOPIC=<topic>"; exit 1; }
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-console-consumer.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --topic $(TOPIC) --from-beginning \
		--property print.timestamp=true --property print.partition=true --property print.offset=true \
		--property print.headers=true --property print.key=true
kafka-cluster:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-metadata-quorum.sh --bootstrap-server $(KAFKA_BOOTSTRAP) describe --status
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-metadata-quorum.sh --bootstrap-server $(KAFKA_BOOTSTRAP) describe --replication
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-topics.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --describe --under-replicated-partitions
kafka-offsets:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-get-offsets.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --topic $(or $(TOPIC),polaris.order.lifecycle)
kafka-groups:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-consumer-groups.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --describe --all-groups
kafka-leaders:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-leader-election.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --election-type preferred --all-topic-partitions
kafka-stop-%:
	docker compose stop kafka-$*
kafka-start-%:
	docker compose start kafka-$*
playwright-ui:
	playwright-cli open https://polaris.local/swagger-ui/index.html
playwright-close:
	playwright-cli close-all

