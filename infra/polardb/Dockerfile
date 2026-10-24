# PolarDB-X Engine (the open-source PolarDB MySQL-compatible engine, MySQL 8.0.42 core, single-node X-Cluster)
# plus our config and first-start script. The base is CentOS 7 with no working package mirrors, so no tools are
# added; ping and curl are already there. The base is pinned by digest; change it here and nowhere else.
ARG BASE_IMAGE=polardbx/polardbx-engine:v2.5.0_8.4.21@sha256:e2bab692581ad5f24894ac5e7aecacf8eb5eab0787787802fcfb8e9348fd707f
FROM ${BASE_IMAGE}

ARG TZ=Asia/Singapore

COPY my.cnf entrypoint.sh /polardb/

# mysqld ignores a world-writable config file, and files copied from a Windows checkout arrive as 777
RUN chmod 644 /polardb/my.cnf /polardb/entrypoint.sh \
    && ln -sf "/usr/share/zoneinfo/${TZ}" /etc/localtime && echo "${TZ}" > /etc/timezone

ENV TZ=${TZ}

ENTRYPOINT ["/bin/bash", "/polardb/entrypoint.sh"]
