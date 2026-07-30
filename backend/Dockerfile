# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS builder

# Install Maven
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Cache dependency layer — only re-run if pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Build the application
COPY src ./src
RUN mvn clean package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS runtime

WORKDIR /app

# Install curl for Docker healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user for security
RUN groupadd --system dbaagent \
    && useradd --system --gid dbaagent --shell /bin/false dbaagent

# Copy built JAR from builder stage
COPY --from=builder /app/target/dba-agent-backend-*.jar app.jar
RUN chown dbaagent:dbaagent app.jar

USER dbaagent

EXPOSE 8080

# JVM tuning: ZGC for low-latency, 3 GB heap, virtual threads already
# enabled in the app.
#
# Heap was 1 GB (#605 "reduced from 2GB to 1GB with more aggressive GC")
# but brain init's SCHEMA_CLASSIFICATION runs ~10 parallel virtual-
# thread sub-classifications that each load hundreds of tables' columns
# + indexes into memory. On a large MySQL schema (aws-rds-master has
# ~600 tables) the parallel load OOMs the 1 GB heap; with
# -XX:+ExitOnOutOfMemoryError the JVM exits cleanly (code 0, no
# shutdown logs — looks like an external SIGTERM) and the brain init
# progress is lost. The container auto-restarts and the cycle repeats
# every ~8 min. Diagnosed only by `ps -o pid,rss,etime` showing the
# JVM lifecycle resetting periodically after exhausting SSH-tunnel
# race, db-scheduler heartbeat, and Hikari pool keepalive theories.
#
# 3 GB on a 15 GB host leaves plenty for postgres, valkey, nginx, OS.
# Use JSON-array ENTRYPOINT (no shell) because Spring properties like
# spring.data.redis.host=valkey have dots in the env var name; a
# shell-form entrypoint strips those before exec'ing java and Spring
# falls back to localhost for everything.
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Xmx3g", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
