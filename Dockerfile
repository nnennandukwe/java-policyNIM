FROM eclipse-temurin:21-jdk-jammy@sha256:f780cc415d168cad9f6a41607092b67fc799f7d4f6237fab6e4f4ff31ee77938 AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:da196dd83cde2d23408db1ce69bfd4b64d6c4f5279f56dfe1ac55be32acca26d

WORKDIR /app
RUN addgroup --system policynim && adduser --system --ingroup policynim policynim
COPY --from=build /workspace/target/java-policynim-0.0.1-SNAPSHOT.jar /app/policynim.jar

USER policynim
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 CMD ["wget", "-qO-", "http://127.0.0.1:8080/healthz"]
ENTRYPOINT ["java", "-jar", "/app/policynim.jar"]
