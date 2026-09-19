# Builds the web UI, then the Spring Boot jar (with the UI inside it), then a small runtime image.
#   docker build -t featureflag .
#   docker run -p 8000:8000 -e MONGODB_URI=mongodb://host:27017/featureflag_db featureflag
# The backend only resets and reseeds a MongoDB on this machine; set FEATUREFLAG_SEED=true to seed a remote one.

FROM oven/bun:1 AS frontend
WORKDIR /app
COPY package.json bun.lock ./
COPY frontend/package.json frontend/package.json
RUN bun install --frozen-lockfile --ignore-scripts
COPY frontend frontend
RUN cd frontend && bun run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle gradle
# The tr strips CRLF line endings a Windows checkout may have added to the wrapper script.
RUN tr -d '\r' < gradlew > gradlew.unix && mv gradlew.unix gradlew && chmod +x gradlew && ./gradlew --no-daemon --version
COPY backend/src src
COPY --from=frontend /app/frontend/dist/ src/main/resources/static/
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=backend /app/backend/build/libs/*.jar app.jar
USER app
ENV PORT=8000 \
    MONGODB_URI=mongodb://mongo:27017/featureflag_db \
    JAVA_OPTS=""
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/api/v1/health/live" || exit 1
ENTRYPOINT ["sh", "-c", "exec java --enable-native-access=ALL-UNNAMED $JAVA_OPTS -jar /app/app.jar"]
