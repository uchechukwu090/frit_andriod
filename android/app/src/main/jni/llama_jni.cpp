#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <algorithm>
#include <cstdint>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global model and context
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static llama_sampler *g_sampler = nullptr;
static std::mutex g_mutex;
static bool g_loaded = false;
static int g_n_ctx = 2048;
static std::string g_last_error;

static JavaVM *g_jvm = nullptr;

static std::string jstring_to_string(JNIEnv *env, jstring str) {
    if (!str) return "";
    const char *chars = env->GetStringUTFChars(str, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

// A token can end mid-way through a multi-byte character, and NewStringUTF
// aborts (CheckJNI) or returns garbage on invalid UTF-8. Keep only whole,
// valid sequences.
static std::string sanitize_utf8(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    const size_t n = in.size();
    while (i < n) {
        const unsigned char c = (unsigned char) in[i];
        size_t len = 0;
        if (c == 0) { i++; continue; }
        else if (c < 0x80) len = 1;
        else if (c >= 0xC2 && c <= 0xDF) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if (c >= 0xF0 && c <= 0xF4) len = 4;
        else { i++; continue; }
        if (i + len > n) break; // truncated tail
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            if ((((unsigned char) in[i + k]) & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { i++; continue; }
        out.append(in, i, len);
        i += len;
    }
    return out;
}

static int pick_threads() {
    int hw = (int) std::thread::hardware_concurrency();
    if (hw <= 0) return 4;
    // Phones are big.LITTLE: using every core drags the fast ones down to the
    // speed of the slow ones. Use roughly the performance cores only.
    if (hw >= 8) return 4;
    return std::max(2, hw / 2 + 1);
}

static bool build_sampler(float temperature) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }

    auto sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    if (!g_sampler) return false;

    if (temperature < 0.001f) {
        llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return true;
}

static void free_all_locked() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_loaded = false;
}

// ============================================================================
// JNI: NativeLib.loadModel(modelPath, nCtx)
// ============================================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_frit_app_core_llm_NativeLib_loadModel(
    JNIEnv *env, jobject /*thiz*/,
    jstring model_path,
    jint n_ctx
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    free_all_locked();
    g_last_error.clear();

    std::string path = jstring_to_string(env, model_path);
    LOGI("Loading model from: %s", path.c_str());

    llama_model_params model_params = llama_model_default_params();
    // mmap is the default: pages load on demand, so a 1-2GB model does not
    // have to be fully resident on a low-RAM phone.

    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        g_last_error = "llama.cpp could not read this file (unsupported architecture, corrupt or incomplete GGUF)";
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    int ctx = (int) n_ctx;
    const int n_train = (int) llama_model_n_ctx_train(g_model);
    if (n_train > 0 && ctx > n_train) ctx = n_train;
    if (ctx < 512) ctx = 512;
    g_n_ctx = ctx;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t) ctx;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = pick_threads();
    ctx_params.n_threads_batch = pick_threads();

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        g_last_error = "Not enough free memory to create the model context (try a smaller model or close other apps)";
        LOGE("%s", g_last_error.c_str());
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_loaded = true;
    LOGI("Model loaded successfully! ctx=%d threads=%d", ctx, pick_threads());
    return JNI_TRUE;
}

// ============================================================================
// JNI: NativeLib.unloadModel()
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_frit_app_core_llm_NativeLib_unloadModel(
    JNIEnv * /*env*/, jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all_locked();
    LOGI("Model unloaded");
}

// ============================================================================
// JNI: NativeLib.isLoaded()
// ============================================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_frit_app_core_llm_NativeLib_isLoaded(
    JNIEnv * /*env*/, jobject /*thiz*/
) {
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// JNI: NativeLib.lastError()
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_frit_app_core_llm_NativeLib_lastError(
    JNIEnv *env, jobject /*thiz*/
) {
    return env->NewStringUTF(sanitize_utf8(g_last_error).c_str());
}

// ============================================================================
// JNI: NativeLib.formatChat(roles, contents)
// Applies the chat template embedded in the GGUF itself, so ANY chat model
// (Qwen, Gemma, Llama, Phi, Mistral...) gets its own correct prompt format.
// Returns "" when the model has no usable template; Kotlin then falls back.
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_frit_app_core_llm_NativeLib_formatChat(
    JNIEnv *env, jobject /*thiz*/,
    jobjectArray roles,
    jobjectArray contents
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !roles || !contents) return env->NewStringUTF("");

    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(roles);
    if (n <= 0 || n != env->GetArrayLength(contents)) return env->NewStringUTF("");

    std::vector<std::string> r(n), c(n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        jstring jr = (jstring) env->GetObjectArrayElement(roles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(contents, i);
        r[i] = jstring_to_string(env, jr);
        c[i] = jstring_to_string(env, jc);
        if (jr) env->DeleteLocalRef(jr);
        if (jc) env->DeleteLocalRef(jc);
        total += r[i].size() + c[i].size();
    }

    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        msgs[i].role = r[i].c_str();
        msgs[i].content = c[i].c_str();
    }

    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), (size_t) n, true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize((size_t) len + 16);
        len = llama_chat_apply_template(tmpl, msgs.data(), (size_t) n, true, buf.data(), (int32_t) buf.size());
    }
    if (len <= 0) return env->NewStringUTF(""); // template not supported by llama.cpp's built-in list

    return env->NewStringUTF(sanitize_utf8(std::string(buf.data(), (size_t) len)).c_str());
}

// ============================================================================
// JNI: NativeLib.generate(prompt, maxTokens, temperature)
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_frit_app_core_llm_NativeLib_generate(
    JNIEnv *env, jobject /*thiz*/,
    jstring prompt,
    jint max_tokens,
    jfloat temperature
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_loaded || !g_model || !g_ctx) {
        g_last_error = "No model loaded";
        return env->NewStringUTF("");
    }
    g_last_error.clear();

    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);

    // Every call is a fresh conversation (Kotlin re-sends the whole history),
    // so the KV cache MUST start empty. Without this, each call stacked on the
    // previous one and the 2048-token context filled up after a few messages.
    llama_memory_clear(llama_get_memory(g_ctx), true);

    std::string prompt_str = jstring_to_string(env, prompt);
    LOGI("Generating with prompt (len=%zu chars)", prompt_str.size());

    // Tokenize. With a null buffer llama_tokenize returns the NEGATIVE of the
    // token count, so ask for the size first and use its absolute value.
    // parse_special=true so template markers like <|im_start|> become the real
    // special tokens instead of being read as plain text.
    int32_t need = llama_tokenize(vocab, prompt_str.c_str(), (int32_t) prompt_str.size(), nullptr, 0, true, true);
    if (need == INT32_MIN) {
        g_last_error = "Prompt too large to tokenize";
        return env->NewStringUTF("");
    }
    if (need < 0) need = -need;
    if (need == 0) {
        g_last_error = "Empty prompt";
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens((size_t) need);
    int32_t n_tok = llama_tokenize(vocab, prompt_str.c_str(), (int32_t) prompt_str.size(), tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tok < 0) {
        g_last_error = "Tokenization failed";
        return env->NewStringUTF("");
    }
    tokens.resize((size_t) n_tok);

    // Leave room for the reply. If the prompt is too long, drop the MIDDLE
    // (old history) and keep the start (system prompt) and the end (latest turn).
    const int n_len = std::max(16, std::min((int) max_tokens, g_n_ctx / 2));
    const int max_prompt = g_n_ctx - n_len - 8;
    if ((int) tokens.size() > max_prompt) {
        const int head = max_prompt / 3;
        const int tail = max_prompt - head;
        std::vector<llama_token> trimmed;
        trimmed.reserve((size_t) max_prompt);
        trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + head);
        trimmed.insert(trimmed.end(), tokens.end() - tail, tokens.end());
        LOGI("Prompt trimmed %zu -> %zu tokens", tokens.size(), trimmed.size());
        tokens.swap(trimmed);
    }

    // Evaluate the prompt in n_batch-sized chunks (a single decode larger than
    // n_batch is rejected, which is what broke any prompt over 512 tokens).
    const int n_batch = std::max(1, (int) llama_n_batch(g_ctx));
    for (int i = 0; i < (int) tokens.size(); i += n_batch) {
        const int cnt = std::min(n_batch, (int) tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, cnt);
        if (llama_decode(g_ctx, batch) != 0) {
            g_last_error = "Failed to evaluate the prompt";
            LOGE("%s", g_last_error.c_str());
            return env->NewStringUTF("");
        }
    }

    if (!build_sampler(temperature)) {
        g_last_error = "Failed to build sampler";
        return env->NewStringUTF("");
    }

    static const char *kStops[] = {"<|im_end|>", "<|endoftext|>", "<end_of_turn>", "<|eot_id|>"};

    std::string result;
    int n_generated = 0;

    while (n_generated < n_len) {
        llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);

        // End-of-generation covers EOS plus the model's own turn-end tokens.
        if (llama_vocab_is_eog(vocab, id)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, id, piece, (int32_t) sizeof(piece), 0, true);
        if (n > 0) result.append(piece, (size_t) n);

        bool stop = false;
        for (const char *s : kStops) {
            const size_t sl = std::char_traits<char>::length(s);
            if (result.size() >= sl && result.compare(result.size() - sl, sl, s) == 0) {
                result.resize(result.size() - sl);
                stop = true;
                break;
            }
        }
        if (stop) break;

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, next) != 0) {
            LOGE("Failed to decode token");
            break;
        }
        n_generated++;
    }

    LOGI("Generated %d tokens", n_generated);
    return env->NewStringUTF(sanitize_utf8(result).c_str());
}

// ============================================================================
// JNI: NativeLib.generateStream(prompt, maxTokens, temperature)
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_frit_app_core_llm_NativeLib_generateStream(
    JNIEnv *env, jobject thiz,
    jstring prompt,
    jint max_tokens,
    jfloat temperature
) {
    return Java_com_frit_app_core_llm_NativeLib_generate(env, thiz, prompt, max_tokens, temperature);
}

// ============================================================================
// JNI: NativeLib.getModelInfo()
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_frit_app_core_llm_NativeLib_getModelInfo(
    JNIEnv *env, jobject /*thiz*/
) {
    if (!g_model) {
        return env->NewStringUTF("No model loaded");
    }

    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_vocab = llama_vocab_n_tokens(vocab);
    int n_ctx_train = llama_model_n_ctx_train(g_model);
    int n_embd = llama_model_n_embd(g_model);

    char desc[128] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));

    std::string info = std::string(desc) + " | Vocab: " + std::to_string(n_vocab) +
                       " | Context: " + std::to_string(n_ctx_train) +
                       " | Embedding: " + std::to_string(n_embd);
    return env->NewStringUTF(sanitize_utf8(info).c_str());
}

// ============================================================================
// JNI: JNI_OnLoad
// ============================================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void * /*reserved*/) {
    g_jvm = jvm;
    LOGI("llama_jni loaded");
    return JNI_VERSION_1_6;
}
