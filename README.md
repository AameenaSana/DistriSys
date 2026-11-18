# DistriSys

DistriSys is a Java 21 prototype for distributed object storage with Reed–Solomon erasure coding. The current implementation stores and verifies individual shards over gRPC and can encode and reconstruct stripes with a standalone 4+2 codec.

The target design splits each object into stripes and stores four data shards and two parity shards per stripe across six distinct nodes. Any four valid shards can reconstruct a stripe. At full stripe size, this uses 1.5× the original data size for shard payloads, compared with 3× for triple replication. These are design ratios before padding and metadata.

## Project status

| Component | Status |
| --- | --- |
| Shard storage | Streaming gRPC writes and reads, CRC32C verification, and a command-line client |
| Local deployment | Docker Compose configuration for two independent storage nodes with separate volumes |
| Erasure coding | GF(2⁸) arithmetic, systematic Vandermonde encoding, and reconstruction from any four valid shards |
| Object storage | Planned HTTP gateway, atomic object-version commits, and a single metadata service backed by a write-ahead log and snapshots |
| Cluster operations | Planned consistent-hash placement, heartbeats, repair, scrubbing, and orphan collection |

## Quick start on Windows

Install JDK 21 and make it available through `JAVA_HOME` or `PATH`. The Gradle wrapper downloads Gradle and project dependencies on the first run.

In PowerShell, from the repository root:

```powershell
.\gradlew.bat build installDist
$env:DATA_DIR = Join-Path $PWD 'build\demo-shards'
.\storage-node\build\install\storage-node\bin\storage-node.bat
```

Keep the node running and open a second PowerShell window at the repository root. Store this README as a shard, retrieve it, and compare the SHA-256 hashes of the two files:

```powershell
$cli = '.\storage-node\build\install\storage-node\bin\shard-cli.bat'
& $cli localhost:9090 put demo-v1 0 0 README.md
$copy = 'build\demo-readme.md'
& $cli localhost:9090 get demo-v1 0 0 $copy
Get-FileHash -Algorithm SHA256 -Path README.md, $copy
```

The CLI reports the byte count and CRC32C for each operation. The two SHA-256 hashes should match. Press **Ctrl+C** in the server window to stop the node.

### Browser shard console

With the storage node running, start the local UI bridge in a second terminal:

```powershell
.\storage-node\build\install\storage-node\bin\shard-ui.bat 127.0.0.1:9090 8080
```

Open [the local shard console](http://127.0.0.1:8080/). Enter a version ID, stripe index, and shard index. Choose a file to upload, or reuse the same three values to download a stored shard. The bridge listens on loopback and forwards each transfer to the configured gRPC node.

### Run two nodes with Docker Compose

With Docker Desktop running in Linux container mode, build the distribution before starting Compose:

```powershell
.\gradlew.bat :storage-node:installDist
docker compose up --build -d
docker compose logs storage-1 storage-2
```

Both nodes listen on port 9090 inside the Compose network. The Compose configuration keeps those ports internal. Run `docker compose down` to stop the cluster and keep its named data volumes.

## Repository layout

| Module | Responsibility |
| --- | --- |
| [`proto`](proto) | Protobuf schema and generated Java gRPC stubs |
| [`storage-node`](storage-node) | gRPC server, on-disk shard format, and the `shard-cli` launcher |
| [`erasure`](erasure) | Dependency-free GF(2⁸) arithmetic and Reed–Solomon matrix operations |

## Tests and durability

Run the test suite with `.\gradlew.bat test`. The suite covers disk round trips, rejected and abandoned writes, corruption detection, in-process gRPC calls, finite-field properties, and byte-level reconstruction for all 15 pairs of missing shards.

The storage node writes each shard to a temporary file, validates the declared length and CRC32C, syncs the file, then publishes it by atomic rename. On Linux it also syncs the containing directory before acknowledging the write. Reads verify both the on-disk header and payload before sending bytes.

The planned metadata service is a single node. Its WAL and snapshots support recovery after restart, while object operations depend on that service being available. Multi-node recovery tests, chaos testing, and benchmark results are planned for later milestones.
