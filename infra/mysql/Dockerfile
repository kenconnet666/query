# Official MySQL 8.4 plus basic ops tools (ping, ss, nc, nano, wget) and the Singapore time zone.
# MySQL 8.4 has no Debian variant, so the base is the official Oracle Linux image and packages come from
# microdnf. The base is pinned by digest; change it here and nowhere else.
ARG BASE_IMAGE=mysql:8.4.11@sha256:1f8411cc085d2f2f92060fca926746cbf97f87b76c3008e9741ad62e604c4ffa
FROM ${BASE_IMAGE}

USER root

ARG TZ=Asia/Singapore

RUN set -eux; \
    microdnf install -y --setopt=install_weak_deps=0 \
        nano wget procps-ng iproute iputils nmap-ncat tzdata; \
    microdnf clean all; \
    ln -sf "/usr/share/zoneinfo/${TZ}" /etc/localtime; \
    echo "${TZ}" > /etc/timezone

ENV TZ=${TZ}
