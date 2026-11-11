#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
for kind in JRE JDK CC; do
    image_var="CODE_${kind}_IMAGE"
    repo_var="CODE_${kind}_REPOSITORY"
    image=${!image_var:-}
    repo=${!repo_var:-}
    # Repository allowlist is an independently reviewed deployment input, not derived from image.
    [[ $repo =~ ^[a-z0-9][a-z0-9./:_-]*$ && $repo != *..* ]] || { echo "missing/invalid approved repository: $repo_var" >&2; exit 64; }
    [[ $image =~ ^[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}$ && ${image%@*} == "$repo" ]] || { echo "invalid pinned image: $image_var" >&2; exit 64; }
done
[[ ${GRADLE_DISTRIBUTION_SHA256:-} =~ ^[0-9a-f]{64}$ ]] || { echo 'trusted Gradle distribution SHA256 required' >&2; exit 64; }
[[ ${CODE_OUTPUT_TAG:-} =~ ^[a-z0-9][a-z0-9./:_-]*$ ]] || { echo 'explicit local output tag required' >&2; exit 64; }
test -s gradle.lockfile
test -s gradle/verification-metadata.xml
# No pull/run/load/install/service operations. CI alone explicitly invokes this helper.
exec docker build --file deploy/Dockerfile --target task \
    --build-arg "CODE_JRE_IMAGE=$CODE_JRE_IMAGE" \
    --build-arg "CODE_JDK_IMAGE=$CODE_JDK_IMAGE" \
    --build-arg "CODE_CC_IMAGE=$CODE_CC_IMAGE" \
    --build-arg "GRADLE_DISTRIBUTION_SHA256=$GRADLE_DISTRIBUTION_SHA256" \
    --tag "$CODE_OUTPUT_TAG" .
