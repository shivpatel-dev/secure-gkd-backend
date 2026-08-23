FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN groupadd --gid 10001 securegkd \
    && useradd --uid 10001 --gid securegkd --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin securegkd

COPY --from=build --chown=securegkd:securegkd /workspace/target/*.jar app.jar

EXPOSE 8080

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
