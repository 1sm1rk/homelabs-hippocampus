# syntax=docker/dockerfile:1

# Multi-stage build for the hippocampus MCP service (server deployment /
# fresh-database test).
#
# The runtime stage ships ONLY the packaged fat jar: the in-process ONNX
# Runtime + DJL tokenizer natives segfault deterministically on the exploded
# classpath (AGENTS.md gotcha), so the jar form is the only stable runtime —
# same as `java -jar target/…jar` on the host. Tests (Testcontainers) need a
# Docker daemon: they run on the host via `mvn verify`, not inside the build.

# ---- Stage 1: build the jar (JDK 25, same Maven line as the host) ----
# Needs BuildKit/buildx (Docker's default builder since 23.x): the .m2 cache
# mount survives pom.xml changes, so dependency re-downloads stay rare.
FROM maven:3.10.0-eclipse-temurin-25-noble AS build
WORKDIR /build

# Dependency layer: cached on pom.xml alone.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -DskipTests

# ---- Stage 2: runtime (JRE 25, glibc-based — the JNI natives need glibc,
# so no alpine/musl variant) ----
FROM eclipse-temurin:25-jre-noble

# Non-root service user. uid/gid 1000 matches the usual first login user on
# the host, so read-only bind mounts (model dir, imported files) line up.
# Non-root service user: the noble base image already ships an `ubuntu` user
# (uid/gid 1000) — reuse it instead of creating a duplicate-uid user.
# uid 1000 matches the usual first login user on the host, so read-only bind
# mounts (model dir, imported files) line up.
WORKDIR /opt/hippocampus

# Container-canonical model location (ADR 0005 loose coupling: the ~449 MB
# artifacts stay OUTSIDE the image and are mounted at runtime). Override these
# env vars to move the mount point. Eager init: a missing or broken model
# aborts startup with the install hint — never a silently broken LTM.
ENV HIPPOCAMPUS_LTM_EMBEDDING_MODEL_PATH=/opt/hippocampus/models/multilingual-MiniLM/model.onnx \
    HIPPOCAMPUS_LTM_EMBEDDING_TOKENIZER_PATH=/opt/hippocampus/models/multilingual-MiniLM/tokenizer.json

RUN mkdir -p /opt/hippocampus/models/multilingual-MiniLM \
 && chown -R ubuntu:ubuntu /opt/hippocampus

COPY --from=build --chown=ubuntu:ubuntu \
     /build/target/homelabs-hippocampus-0.0.1-SNAPSHOT.jar app.jar

USER ubuntu
EXPOSE 8090

# JAVA_TOOL_OPTIONS is picked up by the JVM automatically (heap sizing etc.).
# --enable-native-access=ALL-UNNAMED: JEP 472 (JDK 24+) flag for the ONNX/JNA
# native-load path — same flag as the documented host run command.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/hippocampus/app.jar"]