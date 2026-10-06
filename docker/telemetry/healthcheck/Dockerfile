# Adds a static busybox (wget) to shell-less upstream images so compose can run a
# healthcheck against them. Base image stays the pinned upstream image.
ARG BASE
FROM busybox:1.37.0-musl AS bb
FROM ${BASE}
COPY --from=bb /bin/busybox /busybox
