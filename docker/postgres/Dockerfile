# syntax=docker/dockerfile:1.7
FROM pgvector/pgvector@sha256:1616232566513e94999350040df4a65ac5a01f0d18d6225346ee68714907d79d

# VectorChord-bm25 0.3.0 release artifact: postgresql-14-vchord-bm25,
# AGPL-3.0-only or Elastic-2.0, SHA256 verified before installation.
COPY vchord-bm25.deb /tmp/vchord-bm25.deb

RUN echo "0631499a47bd9de71e93be481e156e089a2cd68852ac2ecc33f9e0ca4a516ea8  /tmp/vchord-bm25.deb" | sha256sum -c - \
    && dpkg -i /tmp/vchord-bm25.deb \
    && rm -f /tmp/vchord-bm25.deb
