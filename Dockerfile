# The jar is built inside the image, never copied from the working tree. A
# Dockerfile that does `COPY target/*.jar` ships whatever happens to be in
# target/ - which is how a months-old build reaches production while the file
# still reads correctly.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing a source file does not
# re-download the world. Only the wrapper and the pom are needed for this.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -Dmaven.test.skip=true

# Spring Boot 4 replaced `-Djarmode=layertools` with `-Djarmode=tools`, and the
# extraction is no longer implied: --layers asks for the layered layout and
# --launcher for the loader classes that JarLauncher needs. Verified against
# this jar - `list-layers` reports dependencies, spring-boot-loader,
# snapshot-dependencies, application, in that order.
RUN java -Djarmode=tools -jar target/ledger-service-*.jar \
        extract --layers --launcher --destination /build/extracted


FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Nothing here needs root, and a container that runs as root turns a single
# escaped process into a root process.
RUN addgroup -S ledger && adduser -S ledger -G ledger

# Least- to most-frequently changed. Dependencies rarely move, application code
# changes every commit; in this order a code change reuses the three layers
# above it instead of rebuilding one fat jar layer.
COPY --from=build --chown=ledger:ledger /build/extracted/dependencies/ ./
COPY --from=build --chown=ledger:ledger /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=ledger:ledger /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=ledger:ledger /build/extracted/application/ ./

USER ledger
EXPOSE 8091

# busybox wget, because alpine has no curl and adding one to run a healthcheck
# is a package more than this needs. /actuator/health is deliberately public -
# a healthcheck cannot present a bearer token.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD wget -qO- http://localhost:8091/actuator/health || exit 1

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container's own
# limit, so the same image behaves sensibly whether it is given 512 MB or 4 GB.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", \
            "org.springframework.boot.loader.launch.JarLauncher"]
