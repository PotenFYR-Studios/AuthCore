# ============================================================================
# AuthCore host-test harness image.
#
# Built per Java major from the official, stable eclipse-temurin image:
#   eclipse-temurin:<major>-jre-noble   -> server runtime (JRE 17/21/25)
#
# Tags: authcore-test:j17 / j21 / j25 (one per Minecraft version group).
# The image bakes curl + jq so the entrypoint can self-provision the server
# files (Fabric server jar, Fabric API, Forge/NeoForge installers) from the
# official APIs; downloads are cached on the bind-mounted .cache volume.
#
# Build context: test/docker/
#   docker build --build-arg JAVA_MAJOR=21 -t authcore-test:j21 .
# ============================================================================
ARG JAVA_MAJOR=21

FROM eclipse-temurin:${JAVA_MAJOR}-jre-noble
ARG JAVA_MAJOR

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl jq \
 && rm -rf /var/lib/apt/lists/*

COPY server-entrypoint.sh /usr/local/bin/authcore-entrypoint.sh
RUN chmod +x /usr/local/bin/authcore-entrypoint.sh

# Runs as root so it can authoritatively clean bind-mounted server state
# (files written by an earlier root container cannot be removed by a
# non-root user on Linux CI).
ENTRYPOINT ["/usr/local/bin/authcore-entrypoint.sh"]
