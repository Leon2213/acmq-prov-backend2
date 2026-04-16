FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Adjust the filename to match your build output (e.g. target/acmq-prov-backend.jar)
COPY *.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
