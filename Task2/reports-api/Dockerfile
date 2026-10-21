FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --uid 10001 --create-home app
COPY --from=build /app/target/reports-api-1.0.0.jar app.jar
USER app
EXPOSE 8001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
