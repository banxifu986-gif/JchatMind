# Third-Party Notices

## VectorChord-bm25

- Component: `postgresql-14-vchord-bm25`
- Version: `0.3.0`
- Maintainer: `Tensorchord <support@tensorchord.ai>`
- Upstream homepage: https://github.com/tensorchord/VectorChord-bm25/
- Upstream package license field: `AGPL-3.0-only or Elastic-2.0`
- License adopted for this repository's VectorChord runtime artifact: `Elastic-2.0`
- Artifact path: `docker/postgres/vchord-bm25.deb`
- Artifact SHA-256: `0631499a47bd9de71e93be481e156e089a2cd68852ac2ecc33f9e0ca4a516ea8`

The artifact is an offline build input. Its identity is verified by the SHA-256
value in `docker/postgres/Dockerfile` before installation. The package control
metadata was inspected with `dpkg-deb --info` on 2026-08-23 and records the
component name, version, maintainer, homepage, and dual-license field above.

The JChatMind source code remains licensed under the repository's MIT
`LICENSE`. Distribution or operation of the VectorChord runtime artifact must
also comply with Elastic License 2.0. This notice does not grant rights beyond
that license.
