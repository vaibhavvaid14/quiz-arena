# Quiz Arena on the JVM.
#
# Render has no native JVM runtime, so the service is built and run as a
# container. Two stages keep the shipped image small: Gradle and the JDK are
# only needed to build, and the result is one runnable jar on a JRE base.

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# The wrapper and build scripts first: these layers are cached until the build
# definition itself changes, so ordinary source edits do not re-resolve Gradle.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY shared/build.gradle.kts ./shared/
COPY server/build.gradle.kts ./server/
COPY client/build.gradle.kts ./client/
RUN chmod +x gradlew && ./gradlew --no-daemon :server:dependencies --quiet || true

COPY shared ./shared
COPY server ./server
COPY client ./client
RUN ./gradlew --no-daemon :server:buildFatJar

# ----------------------------------------------------------------- run
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /src/server/build/libs/server-all.jar ./quiz-arena.jar
# The web app's static files; the question bank travels inside the jar.
COPY index.html ./
COPY css ./css
COPY js ./js

# Render provides PORT; HOST must be 0.0.0.0 for the container to accept traffic.
ENV HOST=0.0.0.0 \
    PORT=8000 \
    QUIZ_DB=/app/data/quiz.db \
    QUIZ_STATIC=/app

RUN mkdir -p /app/data
EXPOSE 8000

# Container memory is small on a free plan; let the JVM size itself to the
# cgroup limit rather than assuming the host's RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/quiz-arena.jar"]
