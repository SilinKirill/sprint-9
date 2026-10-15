FROM maven:3.9.9-eclipse-temurin-17 AS provider
WORKDIR /build
COPY yandex-provider/pom.xml .
RUN mvn -q dependency:go-offline
COPY yandex-provider/src ./src
RUN mvn -q package

FROM quay.io/keycloak/keycloak:21.1.2
COPY --from=provider --chown=keycloak:keycloak /build/target/keycloak-yandex-1.0.0.jar /opt/keycloak/providers/
COPY --chown=keycloak:keycloak entrypoint.sh /opt/keycloak/bin/bionicpro-entrypoint.sh
RUN chmod +x /opt/keycloak/bin/bionicpro-entrypoint.sh && /opt/keycloak/bin/kc.sh build --db=postgres
ENTRYPOINT ["/opt/keycloak/bin/bionicpro-entrypoint.sh"]
