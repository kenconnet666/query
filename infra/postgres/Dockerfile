# Official PostgreSQL 17 on Debian bookworm plus basic ops tools (ping, curl, ss, nc, nano, wget) and the
# Singapore time zone. The base is pinned by digest; change it here and nowhere else.
ARG BASE_IMAGE=postgres:17.11-bookworm@sha256:66aafa11cf15800a3c94763f7e11d1e7b2e37e5e84bc4ce027cb6e1e4bfaf4df
FROM ${BASE_IMAGE}

USER root

# Set to a mirror host such as mirrors.aliyun.com when deb.debian.org is slow; empty keeps the default.
ARG APT_MIRROR=
ARG TZ=Asia/Singapore

RUN set -eux; \
    if [ -n "$APT_MIRROR" ]; then sed -i "s#deb.debian.org#${APT_MIRROR}#g" /etc/apt/sources.list.d/debian.sources; fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl wget nano procps iproute2 iputils-ping netcat-openbsd tzdata; \
    rm -rf /var/lib/apt/lists/*; \
    ln -sf "/usr/share/zoneinfo/${TZ}" /etc/localtime; \
    echo "${TZ}" > /etc/timezone

ENV TZ=${TZ}
