FROM public.ecr.aws/docker/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY control-plane/pom.xml control-plane/pom.xml
COPY agent-bridge/pom.xml agent-bridge/pom.xml
COPY control-plane/src control-plane/src
COPY workspace-mcp workspace-mcp
COPY workspace-mcp/README.md control-plane/src/main/resources/static/downloads/workspace-mcp.md
COPY LICENSE LICENSE
RUN mkdir -p /tmp/package /src/control-plane/src/main/resources/static/downloads \
    && cp workspace-mcp/package.json workspace-mcp/README.md LICENSE /tmp/package/ \
    && cp -r workspace-mcp/src /tmp/package/ \
    && tar -C /tmp -czf /src/control-plane/src/main/resources/static/downloads/workspace-mcp.tgz package
RUN mvn -q -pl control-plane -am package -DskipTests

FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/control-plane/target/control-plane-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
