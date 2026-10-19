// FRIT LLM gateway — OpenRouter-only (Groq + DeepSeek removed).
// env: OPENROUTER_KEY,
//      OR_PRIMARY (z-ai/glm-5.3-flash), OR_FAST (openai/gpt-oss-20b),
//      OR_FREE_FAST (openai/gpt-oss-20b:free), OR_FREE_DRAFT (qwen/qwen3-30b-a3b-2507:free),
//      CPU_URL, CPU_KEY, GPU_SPACE, HF_TOKEN,
//      OR_IMAGE (google/gemini-2.5-flash-image), OR_STT (openai/whisper-large-v3)
import { Client } from "@gradio/client";

const env = process.env;
let gpuClient;

const OR_BASE = "https://openrouter.ai/api/v1";
const OR_KEY = env.OPENROUTER_KEY || env.OPENROUTER_API_KEY || "";
const OR_PRIMARY = env.OR_PRIMARY || "z-ai/glm-5.3-flash";
const OR_FAST = env.OR_FAST || "openai/gpt-oss-20b";
const OR_FREE_FAST = env.OR_FREE_FAST || "qwen/qwen3.8-27b:free";
const OR_FREE_DRAFT = env.OR_FREE_DRAFT || "nvidia/nemotron-3-super-120b-a12b:free";

async function openai(base, key, model, messages, tools, maxTokens, extraHeaders = {}) {
  const res = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${key}`,
      "HTTP-Referer": "https://frit.local",
      "X-Title": "FRIT",
      ...extraHeaders,
    },
    body: JSON.stringify({ model, messages, tools: tools?.length ? tools : undefined, max_tokens: maxTokens }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok) throw new Error(`${base} ${model} ${res.status}`);
  const msg = (await res.json()).choices[0].message;
  return { content: msg.content ?? "", tool_calls: msg.tool_calls ?? [] };
}

const cpu = (m, t, n) => openai(env.CPU_URL + "/v1", env.CPU_KEY, "frit-cpu", m, t, n);
// Paid lowest-cost first. Free :free quota (1000/day) is fallback only —
// preemptible, 429-prone, never primary for tool calls.
const orPrimary = (m, t, n) => openai(OR_BASE, OR_KEY, OR_PRIMARY, m, t, n);
const orFast = (m, t, n) => openai(OR_BASE, OR_KEY, OR_FAST, m, t, n);
const orFree = (m, t, n) => openai(OR_BASE, OR_KEY, OR_FREE_FAST, m, t, n);
const orFreeDraft = (m, t, n) => openai(OR_BASE, OR_KEY, OR_FREE_DRAFT, m, t, n);

async function gpu(messages, tools, maxTokens, imageB64 = "", think = false) {
  gpuClient ??= await Client.connect(env.GPU_SPACE, { hf_token: env.HF_TOKEN });
  const r = await gpuClient.predict("/chat", {
    messages_json: JSON.stringify(messages),
    tools_json: tools?.length ? JSON.stringify(tools) : "",
    image_b64: imageB64,
    max_tokens: maxTokens,
    think,
  });
  return JSON.parse(r.data[0]); // { content, reasoning, tool_calls:[{name,arguments}], tokens }
}

// Voice: OpenRouter STT (whisper-large-v3 accuracy pick).
export async function transcribe(audioB64, format = "wav", language = "en") {
  const res = await fetch(`${OR_BASE}/audio/transcriptions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${OR_KEY}`,
      "HTTP-Referer": "https://frit.local",
      "X-Title": "FRIT",
    },
    body: JSON.stringify({
      model: env.OR_STT || "openai/whisper-large-v3",
      input_audio: { data: audioB64, format },
      language,
    }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok) throw new Error(`STT ${res.status}`);
  return (await res.json()).text ?? "";
}

// Image: OpenRouter Images API (gemini-2.5-flash-image default).
export async function generateImage(prompt, opts = {}) {
  const res = await fetch(`${OR_BASE}/images`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${OR_KEY}`,
      "HTTP-Referer": "https://frit.local",
      "X-Title": "FRIT",
    },
    body: JSON.stringify({
      model: env.OR_IMAGE || "google/gemini-2.5-flash-image",
      prompt,
      ...(opts.aspect_ratio ? { aspect_ratio: opts.aspect_ratio } : {}),
      ...(opts.n ? { n: opts.n } : {}),
    }),
    signal: AbortSignal.timeout(180_000),
  });
  if (!res.ok) throw new Error(`IMG ${res.status}`);
  return res.json();
}

// hard=true or an image -> GPU first, then paid OpenRouter. Otherwise paid
// OpenRouter first, free quota second, self-host last. Paid never skipped:
// free 429s fall through silently.
export async function ask({ messages, tools = [], imageB64 = "", hard = false, maxTokens = 1024 }) {
  const order = imageB64 || hard
    ? ["gpu", "orPrimary", "orFast", "orFree", "cpu"]
    : ["orPrimary", "orFast", "orFree", "orFreeDraft", "cpu", "gpu"];
  const fns = { gpu: null, orPrimary, orFast, orFree, orFreeDraft, cpu };
  let lastErr;
  for (const name of order) {
    try {
      if (name === "gpu") return { via: name, ...(await gpu(messages, tools, maxTokens, imageB64, hard)) };
      if (imageB64 && name.startsWith("or")) {
        // GLM-5.3-Flash is multimodal so orPrimary can take images; :free
        // endpoints often can't — skip free on image input.
        if (name !== "orPrimary" && name !== "orFast") continue;
      }
      return { via: name, ...(await fns[name](messages, tools, maxTokens)) };
    } catch (e) {
      lastErr = e; // quota exhausted, 429, Space asleep: try next
    }
  }
  throw lastErr;
}
