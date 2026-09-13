# Docker Compose deployment

This repo now includes a Docker Compose deployment for `audio-cpp-hub`.

## Important: do not run it alongside systemd on port 18080

The Compose deployment now owns port `18080` and the GPU/model. The systemd deployment was stopped and disabled before migration:

```bash
sudo systemctl disable --now audio-cpp-hub.service
```

The old standalone `audio-breeze.service` remains disabled. Do not start either systemd unit while Compose is running.

## Start with Compose

From `/home/matthewh/audio.cpp-hub`:

```bash
docker compose up -d --build
```

The service publishes:

```text
http://<host>:18080
```

It starts the hub, creates the `breeze` child if absent, waits for READY, and sends a warm-up speech request before the container startup script continues. The container uses Docker's `unless-stopped` restart policy. Docker itself must be enabled at host boot for Compose to start automatically:

```bash
sudo systemctl enable --now docker
```

Current migrated state: the container is healthy, `breeze` is READY on child port `18090`, and both local and LAN API requests have returned valid WAV audio. A real generation load measurement is recorded in [`LOAD_REPORT.md`](LOAD_REPORT.md); it observed roughly 87–92% GPU utilization, approximately 5.1 GiB steady VRAM with transient peaks up to about 7.7 GiB, and approximately 1.6 GiB container memory.

Check status and logs:

```bash
docker compose ps
docker compose logs -f audio-cpp-hub
curl http://127.0.0.1:18080/v1/models
curl http://127.0.0.1:18080/api/instances
```

## Existing weights and binary

No GGUF is copied into the image. Compose mounts these host files read-only:

```text
/home/matthewh/audio.cpp/bin/audiocpp_server
/home/matthewh/audio.cpp/breeze/breeze-tts-2-q8_0.gguf
```

Inside the container they appear as:

```text
/audio.cpp/bin/audiocpp_server
/audio.cpp/breeze/breeze-tts-2-q8_0.gguf
```

The hub runtime state is persisted in the repository's `data/`, `logs/`, and `run/` directories.

## GPU/Vulkan caveat

The Compose file requests NVIDIA GPU access and maps `/dev/dri`. The image contains the Vulkan loader and the X11 runtime libraries required by the NVIDIA Vulkan ICD. The host NVIDIA Container Toolkit injects the NVIDIA driver libraries and ICD manifest. Compose also mounts the host's `/lib/x86_64-linux-gnu` and `/usr/lib/x86_64-linux-gnu` read-only and puts them first in `LD_LIBRARY_PATH`; this is required because the existing audio.cpp binary was linked against the host's Vulkan/GLVND stack. The current `audiocpp_server` remains the existing CPU+Vulkan binary; it is not CUDA-enabled.

On this host, the probe must use the Compose mounts and NVIDIA runtime; the working probe reports the GTX 1080 as `Vulkan:0`. Validate after changes before switching production:

```bash
docker compose -f compose.test.yaml run --rm --no-deps \
  --entrypoint /bin/bash audio-cpp-hub \
  -lc '/audio.cpp/bin/audiocpp_server --list-devices'
```

It must report the NVIDIA GTX 1080 as `Vulkan:0` before using the Compose deployment for Breeze. If it reports only `CPU:0`, stop Compose and investigate before serving traffic: the child would not have the required GPU path. The probe uses the production mounts for the executable, its bundled Vulkan loader, the host GLVND libraries, and the GGUF path.

## Isolated validation

`compose.test.yaml` maps the hub to host port `28080` and uses named test volumes, so it can be used without disturbing the systemd service:

```bash
docker compose -f compose.test.yaml run --rm --no-deps \
  --entrypoint /bin/bash audio-cpp-hub \
  -lc '/audio.cpp/bin/audiocpp_server --version && /audio.cpp/bin/audiocpp_server --list-devices'
```

Do not run the full test service while systemd owns the same GPU unless you are only doing the executable/device probe; two warmed Breeze processes can compete for VRAM.

## API test after migration

```bash
curl -fS --max-time 240 \
  http://127.0.0.1:18080/v1/audio/speech \
  -H 'Content-Type: application/json' \
  --data-raw '{"model":"breeze","input":"Docker Compose Breeze test.","instructions":"Speak clearly and naturally.","response_format":"wav"}' \
  -o /tmp/breeze-compose.wav

file /tmp/breeze-compose.wav
```
