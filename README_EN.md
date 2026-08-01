# audio.cpp-hub

**[简体中文](README.md) | English**

A web management panel for [audio.cpp](https://github.com/0xShug0/audio.cpp): a lightweight HTTP service written in Java (Netty) that starts / stops / monitors multiple `audiocpp_server` model-instance subprocesses, and provides a primarily-Chinese web UI for TTS / ASR / music-separation and other audio tasks.

Repository: <https://github.com/IIIIIllllIIIIIlllll/audio.cpp-hub>

## Features

- **Multi-instance management**: generates a config for each model instance and launches `audiocpp_server` as a subprocess — automatic port allocation (bound to 127.0.0.1), health polling, log viewing, one-click stop
- **Web UI**: plain HTML/JS frontend (no build step), bilingual Chinese/English, with model selection, parameter forms and task submission
- **TTS operation history**: per-model history of the latest 50 entries / 500MB of synthesis records and result audio, replayable anytime from a right-hand sidebar, with lazy audio loading
- **OpenAI-compatible proxy**: `GET /v1/models` lists all ready instances; endpoints like `POST /v1/audio/speech` are routed by service name and forwarded — large base64 payloads are streamed through disk the whole way and never enter the JVM heap, so any OpenAI client works out of the box
- **Optional HTTPS**: TLS and plain HTTP are auto-detected on the same port (plain HTTP gets a 308 redirect), with built-in one-click generation of a self-signed CA + server certificate
- **Windows-friendly**: system tray, registry-based auto-start, and a C launcher (double-click to run with an embedded JRE)
- **Lightweight**: the hub itself loads no models; recommended JVM flags are `-Xms128m -Xmx128m`

## Quick Start

### Using a release package (recommended)

Download from [Releases](https://github.com/IIIIIllllIIIIIlllll/audio.cpp-hub/releases):

- Platform-independent base package: requires **Java 21+** installed on the system
- `-windows` / `-linux` full packages: bundled JRE and launcher, ready to run after extraction

Release packages **do not include the audio.cpp binaries**. Download `audiocpp_server` yourself (place it anywhere, e.g. `audiocpp/`), then register it as an executable in the web UI. See `model_download_urls.md` in the repository for model weight download links.

### Building from source

Requires JDK 21+. The project is compiled directly with `javac` — **Maven is not used for building** (`pom.xml` only exists as an Eclipse project stub):

```bash
# Compile (use ; as the classpath separator on Windows)
javac -encoding UTF-8 -d build/classes -cp "lib/*" $(find src/main/java -name "*.java")
cp -r src/main/resources/* build/classes/

# Run
java -cp "build/classes;lib/*" org.mark.audiocpp.hub.AudioHubServer   # Windows
java -cp "build/classes:lib/*" org.mark.audiocpp.hub.AudioHubServer   # Linux
```

The working directory must contain `web/` and `lib/`. After startup, visit `http://127.0.0.1:18080` (port is configured in `hub.config.json`).

Optional: build the C launcher (requires CMake 3.16+ and a compiler) — see [launcher/README.md](launcher/README.md).

## Basic Usage

1. **Register an executable**: add the path to `audiocpp_server` in the UI (each entry can have its own `env` variables; values support `${VAR}` placeholders)
2. **Create a launch profile**: pick a model and parameters (model weight paths can be picked via the built-in file browser)
3. **Start an instance**: the hub writes `run/<id>/server.json` and launches the subprocess; once the health check passes it is ready to use
4. **Submit tasks**: run inference directly in the web UI, or call the OpenAI-compatible API (`model` = instance service name)

## Configuration

`hub.config.json` (auto-generated on first run):

```json
{
  "httpPort": 18080,
  "instancePortBase": 18090,
  "proxyMaxBodyBytes": 1073741824,
  "https": {
    "enabled": true,
    "keystorePath": "ssl/keystore.p12",
    "keystorePassword": "..."
  }
}
```

- `httpPort`: the port the hub listens on (code default 8080)
- `instancePortBase`: starting port for model instance allocation
- `proxyMaxBodyBytes`: on-disk body size limit for `/v1/*` proxy requests (default 1GB)
- `https`: optional; when enabled, TLS is auto-detected on the same port and plain HTTP gets a 308 redirect. Certificates can be generated via the UI / `POST /api/cert/generate`; a restart is required after generating a certificate or changing this config

## API Overview

| Endpoint | Description |
| --- | --- |
| `GET /api/models` | Supported model list (`resources/models.json`) |
| `POST /api/run/<instanceId>` | Task forwarding (TTS uses a streaming pipeline; results are saved to history) |
| `GET /api/history/<modelId>` etc. | TTS history query / audio retrieval / deletion |
| `GET /v1/models`, `POST /v1/*` | OpenAI-compatible proxy |
| `GET /api/events` | Instance event log |
| `/api/fs/*` | Server-local filesystem browsing (for picking model weight paths) |
| `/api/cert/*` | HTTPS certificate status / generation / download |

## Directory Layout

```
src/main/java/org/mark/audiocpp/hub/   # Java sources (entry point: AudioHubServer)
web/                                   # Frontend static files (no build step)
launcher/                              # C launcher (CMake build)
lib/                                   # Local dependency jars (Netty / Gson / SLF4J + Log4j2)
run/                                   # Runtime: instance server.json / logs / proxy cache
data/                                  # Runtime: uploads, voice library, launch profiles, TTS history
logs/                                  # Runtime: per-instance routed logs (kept for 7 days)
```

## Security Notice

> **This is a LAN / localhost tool with no authentication and no public-internet hardening.**
>
> Do not expose the port directly to the public internet. The `/api/fs/*` endpoints intentionally expose browsing of the server's local filesystem — this is by design. For remote access, put a reverse proxy with HTTPS and authentication in front of it yourself.

## Tech Stack

- Java 21, dependencies shipped as local jars (`lib/`): Netty 4.1, Gson, SLF4J + Log4j2
- Frontend: plain HTML/CSS/JS, bilingual Chinese/English (`web/i18n.js`)
- Releases: GitHub Actions automatically compiles and packages three zips (base package / Linux full package / Windows full package)
