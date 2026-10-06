# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build

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

RUN mvn clean package -pl apps/polaris -am -DskipTests -B

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=build /app/apps/polaris/target/*.jar app.jar

VOLUME /app/data

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
