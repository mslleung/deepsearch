FROM eclipse-temurin:23-jre-alpine

WORKDIR /app

# Copy the fat JAR built by Gradle (./gradlew :presentation:shadowJar)
COPY presentation/build/libs/deepsearch-all.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
