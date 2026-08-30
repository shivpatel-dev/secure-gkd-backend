FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw ./
COPY audit-service/pom.xml ./pom.xml
COPY audit-service/src/ src/

RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN groupadd --gid 10002 securegkdaudit \
    && useradd --uid 10002 --gid securegkdaudit --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin securegkdaudit

COPY --from=build --chown=securegkdaudit:securegkdaudit /workspace/target/*.jar app.jar

USER 10002:10002

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
