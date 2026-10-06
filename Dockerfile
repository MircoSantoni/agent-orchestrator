FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY control-plane/pom.xml control-plane/pom.xml
COPY agent-bridge/pom.xml agent-bridge/pom.xml
COPY control-plane/src control-plane/src
RUN mvn -q -pl control-plane -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/control-plane/target/control-plane-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
