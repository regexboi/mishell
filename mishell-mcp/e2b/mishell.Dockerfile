FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    curl \
    findutils \
    gawk \
    git \
    grep \
    nodejs \
    npm \
    python3 \
    python3-pip \
    ripgrep \
    sed \
    tar \
    unzip \
  && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1000 user
USER user
WORKDIR /home/user
