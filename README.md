# DistriSys

DistriSys is a Java 21 prototype for distributed object storage with Reed–Solomon erasure coding. The current implementation stores and verifies individual shards over gRPC and includes the finite-field arithmetic and generator matrix for a 4+2 codec.

The target design splits each object into stripes and stores four data shards and two parity shards per stripe across six distinct nodes. Any four valid shards can reconstruct a stripe. At full stripe size, this uses 1.5× the original data size for shard payloads, compared with 3× for triple replication. These are design ratios before padding and metadata.

## Project status

| Component | Status |
| --- | --- |
| Shard storage | Streaming gRPC writes and reads, CRC32C verification, and a command-line client |
| Local deployment | Docker Compose configuration for two independent storage nodes with separate volumes |
| Erasure coding | GF(2⁸) arithmetic and a systematic 6×4 Vandermonde generator. Byte-level encoding and reconstruction are next |
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

Run the test suite with `.\gradlew.bat test`. The suite covers disk round trips, rejected and abandoned writes, corruption detection, in-process gRPC calls, finite-field properties, and invertibility for all 15 pairs of missing generator rows.

The storage node writes each shard to a temporary file, validates the declared length and CRC32C, syncs the file, then publishes it by atomic rename. On Linux it also syncs the containing directory before acknowledging the write. Reads verify both the on-disk header and payload before sending bytes.

The planned metadata service is a single node. Its WAL and snapshots support recovery after restart, while object operations depend on that service being available. Multi-node recovery tests, chaos testing, and benchmark results are planned for later milestones.
