FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:23-jdk AS backend-build
WORKDIR /src
COPY backend/gradlew backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
COPY backend/src ./src
COPY backend/build/libs ./build/libs
RUN if [ ! -f build/libs/*-with-dependencies.jar ]; then ./gradlew build -x test --no-daemon; fi

FROM eclipse-temurin:23-jre
WORKDIR /app

ARG S6_OVERLAY_VERSION=3.2.0.2
ARG OTEL_JAVA_AGENT_VERSION=2.27.0

RUN apt-get update && apt-get install -y nginx gettext bash curl xz-utils && \
    rm -rf /var/lib/apt/lists/* && \
    if [ "$(uname -m)" = "x86_64" ]; then ARCH="x86_64"; \
    elif [ "$(uname -m)" = "aarch64" ]; then ARCH="aarch64"; \
    else echo "Unsupported architecture: $(uname -m)"; exit 1; fi && \
    curl -L -s "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-noarch.tar.xz" | tar -Jxpf - -C / && \
    curl -L -s "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-${ARCH}.tar.xz" | tar -Jxpf - -C /

RUN useradd -M -u 1001 appuser

COPY --from=frontend-build /app/dist /usr/share/nginx/html
COPY --from=backend-build /src/build/libs/*-with-dependencies.jar /app/app.jar
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY docker/rootfs/ /

RUN mkdir -p /var/lib/nginx/tmp /var/log/nginx /run/nginx /etc/nginx/conf.d && \
    chown -R appuser:appuser /var/lib/nginx /var/log/nginx /run/nginx /etc/nginx/conf.d /usr/share/nginx/html /app /otel /etc/account && \
    chmod +x /etc/s6-overlay/s6-rc.d/*/run /etc/s6-overlay/s6-rc.d/init-config/up /etc/s6-overlay/s6-rc.d/*/finish /usr/local/bin/migrate 2>/dev/null || true

ENTRYPOINT ["/init"]
