# SAP HANA Express as SAP ships it (SUSE based, no extra packages: the image has no package repositories),
# with this folder's scripts baked in so the folder deploys on its own, without a bind mount.
# The base is pinned by digest; change it here and nowhere else.
ARG BASE_IMAGE=saplabs/hanaexpress:2.00.088.00.20251110.1@sha256:432be2785506e97cefc413f495393fc22fcdda034c2c80db2f0f88efeb16dfaf
FROM ${BASE_IMAGE}

COPY start.sh health.sh configure.sh /query/
