# Build context: reviewed worker source root. Never use mutable image tags.
# Docker ARG has no :? expansion: build-image.sh validates before Docker is called.
ARG CODE_JRE_IMAGE
ARG CODE_JDK_IMAGE
ARG CODE_CC_IMAGE
FROM ${CODE_CC_IMAGE} AS native
WORKDIR /native
COPY deploy/launcher.c ./launcher.c
# Required builder contract: gcc + static libc + readelf, already in approved image.
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static -fno-ident -Wl,--build-id=none -o launcher launcher.c \
    && ! readelf -l launcher | grep -q INTERP \
    && ! readelf -d launcher | grep -q NEEDED

FROM ${CODE_JDK_IMAGE} AS worker
ARG GRADLE_DISTRIBUTION_SHA256
WORKDIR /build
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/wrapper/ ./gradle/wrapper/
COPY src/ ./src/
COPY deploy/locking.init.gradle ./deploy/locking.init.gradle
# Reviewed release inputs; bootstrap generation alone is not independent verification.
COPY gradle.lockfile ./gradle.lockfile
COPY gradle/verification-metadata.xml ./gradle/verification-metadata.xml
RUN test "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[ ]*java.specification.version = //p')" = 21 \
    && test "${#GRADLE_DISTRIBUTION_SHA256}" = 64 \
    && printf '\ndistributionSha256Sum=%s\n' "$GRADLE_DISTRIBUTION_SHA256" >> gradle/wrapper/gradle-wrapper.properties \
    && sh ./gradlew --no-daemon --dependency-verification strict -I deploy/locking.init.gradle test installDist exportResources \
    && test "$(head -n 1 build/isolated-resources/easyshell-resource-version.txt)" = 25.2.4 \
    && chmod -R a-w build/install/easyshell-code-worker/lib build/isolated-resources

FROM ${CODE_JRE_IMAGE} AS approved_jre
# This stage requires a complete Linux JRE21 filesystem, with JVM at this fixed path.
# Copying the full root preserves its own dynamic loader/native libraries, not host JDK.
FROM scratch AS task
COPY --from=approved_jre / /
COPY --from=worker /build/build/install/easyshell-code-worker/lib/ /opt/code-worker/lib/
COPY --from=worker /build/build/isolated-resources/ /opt/code-worker/resources/
COPY --from=native --chmod=0555 /native/launcher /opt/code-worker/launcher
USER 65532:65532
WORKDIR /tmp
ENTRYPOINT ["/opt/code-worker/launcher"]
