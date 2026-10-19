# FRIT-1B — local replacement for Groq bill (1.8GB peak)

Goal: replace high-volume `groq:qwen/qwen3.8-27b` (tools/agent) + `groq:openai/gpt-oss-20b` (fast/chat)
calls in `server/src/index.js:122-128` with on-device 1B. Keep Groq only as fallback in
`chatWithFallback` (`server/src/index.js:214`).

## Base model pick: Qwen3-1.7B-Instruct

Why this one, not Llama-3.2-1B or training from zero:
- Same family/tokenizer/tool template as your teacher `qwen3.8-27b` -> 1:1 distillation, no format rewrite.
- Apache-2.0 -> commercial use, no lock-in.
- Has `/think` reasoning mode -> distill `<think> + tool_call` traces directly.
- Q4_K_M via your vendored `android/app/src/main/jni/llama.cpp`:
  ~1.05GB weights + ~0.4GB KV (2k ctx, GQA) + ~0.3GB runtime = ~1.7GB peak.
- Fallback if phone spikes: Qwen3-0.6B, same pipeline.

What it will / won't replace:
- WILL: `open_app, read_screen, tap, type`, simple 1-3 step tool chains, fast chat. This is 70-90% of call volume.
- WON'T: open-ended coding, vision reasoning, 5+ step recovery where 27B/120B wins. Keep those on Groq fallback.

## Pipeline (Colab-Free feasible, no from-scratch pretrain)

1. `export_ledger.py` — dump `server/frit.db` `agent_steps` (tool_calls, tool_results) -> JSONL.
2. `make_distill.py` — for each failed/good episode, prompt teacher (Claude / this assistant) to write
   corrected `<think> + tool_call` JSON. Output: `data/sft.jsonl`.
3. `train_lora_colab.py` — QLoRA (4-bit base, r=16) on 1.7B, T4 ~10-15h for 10-20k examples. Free-tier OK.
4. `export_gguf.sh` — merge LoRA -> convert -> `Q4_K_M` quant.
5. `ram_bench.py` — assert peak RSS < 1.8GB at 2k ctx, measure tool-call accuracy on held-out ledger.

## Recursive learning — realistic version

True on-phone weight self-training doesn't work (needs 8-12x RAM for optimizer+grads, causes
collapse/forgetting). Do this instead:

- On-device: NO training. Only SQLite episodic memory (your existing `agent_steps` ledger) + RAG.
- Nightly offline: export day's failures -> teacher correction -> LoRA fine-tune -> eval gate ->
  promote adapter only if tool-F1 improves. This is the loop that compounds without collapse.

See `loop/NIGHTLY_LOOP.md`.
