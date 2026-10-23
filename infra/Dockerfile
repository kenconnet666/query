# Adds basic ops tools (ping, curl, ss, ...) and the Singapore time zone on top of an official database image.
# One file serves both families: Debian images (PostgreSQL) use apt, the Oracle Linux images (MySQL 8.4 has
# no Debian variant) use microdnf. Build through compose.yaml, which passes BASE_IMAGE.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

USER root

# Set to a mirror host such as mirrors.aliyun.com when deb.debian.org is slow; empty keeps the default.
ARG APT_MIRROR=
ARG TZ=Asia/Singapore

RUN set -eux; \
    if command -v apt-get >/dev/null; then \
        if [ -n "$APT_MIRROR" ]; then sed -i "s#deb.debian.org#${APT_MIRROR}#g" /etc/apt/sources.list.d/debian.sources; fi; \
        apt-get update; \
        apt-get install -y --no-install-recommends \
            ca-certificates curl wget nano procps iproute2 iputils-ping netcat-openbsd tzdata; \
        rm -rf /var/lib/apt/lists/*; \
    else \
        microdnf install -y --setopt=install_weak_deps=0 \
            nano wget procps-ng iproute iputils nmap-ncat tzdata; \
        microdnf clean all; \
    fi; \
    ln -sf "/usr/share/zoneinfo/${TZ}" /etc/localtime; \
    echo "${TZ}" > /etc/timezone

ENV TZ=${TZ}
