# ---------- Build stage ----------
# Base images are pinned by tag and multi-arch index digest (TR-K2).
FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build

WORKDIR /app

# Copy parent and module POMs for layer caching
COPY pom.xml .
COPY libs/polaris-common/pom.xml libs/polaris-common/
COPY libs/polaris-events/pom.xml libs/polaris-events/
COPY libs/polaris-outbox/pom.xml libs/polaris-outbox/
COPY apps/polaris/pom.xml apps/polaris/
COPY apps/polaris-assistant/pom.xml apps/polaris-assistant/
COPY apps/polaris-fulfilment-emulator/pom.xml apps/polaris-fulfilment-emulator/

# Copy source trees
COPY libs ./libs
COPY apps/polaris ./apps/polaris
COPY apps/polaris-assistant ./apps/polaris-assistant
COPY apps/polaris-fulfilment-emulator ./apps/polaris-fulfilment-emulator

RUN mvn clean package -pl apps/polaris-assistant -am -DskipTests -B

# ---------- Runtime stage ----------
FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b

WORKDIR /app

# Numeric non-root UID/GID so Kubernetes `runAsNonRoot` can verify it without resolving a user name (TR-K10).
# The jar stays root-owned and read-only to the app user.
RUN groupadd --system --gid 10001 spring \
 && useradd --system --uid 10001 --gid spring --no-create-home --shell /usr/sbin/nologin spring
USER 10001:10001

COPY --from=build /app/apps/polaris-assistant/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
