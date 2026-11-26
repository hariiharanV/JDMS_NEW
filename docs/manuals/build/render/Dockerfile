FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-writer libreoffice-impress \
    poppler-utils fonts-crosextra-carlito fonts-crosextra-caladea fonts-noto-core fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
