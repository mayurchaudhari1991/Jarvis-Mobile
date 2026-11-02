#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"

#define LOG_TAG "VegLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* sampler = nullptr;
    std::atomic<bool> cancelled{false};
};

Session* as_session(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

// llama.cpp/ggml write log output to stderr by default, which does not reach
// logcat on Android. Routing it through android_log makes load/decode
// failures diagnosable instead of a bare null-return with no explanation.
void ggml_to_android_log(ggml_log_level level, const char* text, void*) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: prio = ANDROID_LOG_INFO; break;
    }
    __android_log_print(prio, LOG_TAG, "%s", text);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_veg_inference_LlamaEngine_nativeInit(JNIEnv*, jobject) {
    llama_log_set(ggml_to_android_log, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_veg_inference_LlamaEngine_nativeLoad(
        JNIEnv* env, jobject, jstring jPath, jint threads, jint contextTokens, jint batchSize) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params modelParams = llama_model_default_params();
    // No GPU offload: Android GPU backends are inconsistent across OEMs and a
    // failed offload is far worse than CPU-only inference.
    modelParams.n_gpu_layers = 0;
    modelParams.use_mmap = true;

    llama_model* model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(jPath, path);

    if (model == nullptr) {
        LOGE("llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx     = static_cast<uint32_t>(contextTokens);
    ctxParams.n_batch   = static_cast<uint32_t>(batchSize);
    ctxParams.n_threads = threads;
    ctxParams.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* session = new Session();
    session->model = model;
    session->ctx   = ctx;
    session->vocab = llama_model_get_vocab(model);

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    session->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("model loaded: ctx=%d threads=%d batch=%d", contextTokens, threads, batchSize);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_veg_inference_LlamaEngine_nativeFree(JNIEnv*, jobject, jlong handle) {
    Session* session = as_session(handle);
    if (session == nullptr) return;
    if (session->sampler) llama_sampler_free(session->sampler);
    if (session->ctx)     llama_free(session->ctx);
    if (session->model)   llama_model_free(session->model);
    delete session;
}

JNIEXPORT void JNICALL
Java_com_veg_inference_LlamaEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    Session* session = as_session(handle);
    if (session != nullptr) session->cancelled.store(true);
}

/**
 * Generates text, calling back into Kotlin once per token.
 *
 * The callback is invoked synchronously on the calling thread, so the Kotlin
 * side must call this from a background dispatcher.
 */
JNIEXPORT void JNICALL
Java_com_veg_inference_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject thiz, jlong handle, jstring jPrompt, jint maxTokens, jobject callback) {

    Session* session = as_session(handle);
    if (session == nullptr) return;

    session->cancelled.store(false);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onDone  = env->GetMethodID(callbackClass, "onDone", "(I)V");
    jmethodID onError = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Tokenize: first call with a null buffer returns the negative count needed.
    const int needed = -llama_tokenize(
            session->vocab, promptStr.c_str(), promptStr.length(),
            nullptr, 0, true, true);

    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(session->vocab, promptStr.c_str(), promptStr.length(),
                       tokens.data(), tokens.size(), true, true) < 0) {
        jstring msg = env->NewStringUTF("Tokenization failed");
        env->CallVoidMethod(callback, onError, msg);
        env->DeleteLocalRef(msg);
        return;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());

    // Holds the single sampled token between iterations. llama_batch_get_one
    // stores a pointer rather than copying, so this must outlive the loop body
    // or the next llama_decode reads freed stack memory.
    llama_token sampled = 0;

    int generated = 0;
    while (generated < maxTokens) {
        if (session->cancelled.load()) break;

        if (llama_decode(session->ctx, batch) != 0) {
            jstring msg = env->NewStringUTF("Decode failed, context may be full");
            env->CallVoidMethod(callback, onError, msg);
            env->DeleteLocalRef(msg);
            return;
        }

        sampled = llama_sampler_sample(session->sampler, session->ctx, -1);

        if (llama_vocab_is_eog(session->vocab, sampled)) break;

        char piece[256];
        const int written = llama_token_to_piece(session->vocab, sampled, piece, sizeof(piece), 0, true);
        if (written > 0) {
            std::string text(piece, written);
            jstring jText = env->NewStringUTF(text.c_str());
            env->CallVoidMethod(callback, onToken, jText);
            env->DeleteLocalRef(jText);
        }

        llama_sampler_accept(session->sampler, sampled);
        batch = llama_batch_get_one(&sampled, 1);
        generated++;
    }

    env->CallVoidMethod(callback, onDone, generated);
}

} // extern "C"
