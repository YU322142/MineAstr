FROM alpine:3.22
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache python3 iproute2 ndisc6 nftables ca-certificates
WORKDIR /app
COPY guardian.py /app/guardian.py
COPY rollback.py /app/rollback.py
LABEL org.opencontainers.image.title="MC IPv6 Guardian" \
      org.opencontainers.image.description="Tracks router IPv6 prefix, maintains a dedicated Minecraft IPv6 relay, and updates only mc.322142.xyz in Cloudflare."
ENV PYTHONUNBUFFERED=1
ENTRYPOINT ["python3", "/app/guardian.py"]
