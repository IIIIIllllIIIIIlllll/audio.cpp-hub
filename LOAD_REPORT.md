# Breeze load report

Measurement date: 2026-09-11 (host local time)

## Deployment measured

- Docker Compose service: `audio-cpp-hub`
- Public API: `http://127.0.0.1:18080`
- Public model: `breeze`
- Child backend: Vulkan
- GPU: NVIDIA GeForce GTX 1080
- GPU VRAM: 8192 MiB total
- GGUF: `/home/matthewh/audio.cpp/breeze/breeze-tts-2-q8_0.gguf`, mounted read-only

## Request

A real `POST /v1/audio/speech` request was run through the public hub endpoint with:

```json
{
  "model": "breeze",
  "input": "This is a short production load check. The service is generating speech while we observe CPU, memory, GPU utilization, and VRAM. This request is intentionally brief.",
  "instructions": "Speak clearly and naturally.",
  "response_format": "wav"
}
```

Result:

- HTTP request succeeded
- Duration: approximately 61 seconds
- Output: 610,604-byte RIFF/WAVE file
- Audio: PCM, 16-bit, mono, 24,000 Hz

## Observed host/container load

During generation, sampled approximately every two seconds:

| Resource | Observation |
|---|---:|
| Docker container CPU | approximately 53–99% of the host's aggregate CPU capacity, typically 58–70% |
| `audiocpp_server` process CPU | approximately 15.6–17.8% from `ps`/container process sampling |
| Container memory | approximately 1.58–1.60 GiB |
| `audiocpp_server` RSS | approximately 1.98–2.05 GiB |
| GPU utilization | generally 87–92%, with brief 100% intervals |
| GPU memory | generally 5,064 MiB, with transient samples around 5,459–7,715 MiB |
| Host load average | approximately 2.9–4.1 during this test |

The apparent difference between container CPU percentage and process CPU percentage is expected: Docker reports usage against the container's available CPU capacity, while the process sample is reported relative to the host's aggregate CPU capacity.

## Interpretation

Breeze is actively using the Vulkan GPU during generation. The stable baseline GPU allocation is about 5.1 GiB, with temporary allocations observed during generation. A single request is operationally viable on this host, but long requests can take about a minute and generate substantial sustained GPU load. Avoid warming multiple Breeze instances on the same GTX 1080 unless VRAM and latency have been intentionally tested.
