#include <jni.h>
#include <cstdint>
#include <opus.h>
#include "webrtc/common_audio/vad/include/webrtc_vad.h"

static void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static bool frame(JNIEnv* env, jbyteArray bytes, int16_t* pcm) {
    if (!bytes || env->GetArrayLength(bytes) != 640) { fail(env, "Expected 20 ms PCM16 mono frame"); return false; }
    jbyte buffer[640];
    env->GetByteArrayRegion(bytes, 0, 640, buffer);
    for (int i = 0; i < 320; ++i) pcm[i] = static_cast<int16_t>(static_cast<uint8_t>(buffer[2*i]) | (static_cast<uint16_t>(static_cast<uint8_t>(buffer[2*i+1])) << 8));
    return true;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_daytrace_app_NativeAudio_createEncoder(JNIEnv* env, jobject, jint bitrate) {
    if (bitrate < 6000 || bitrate > 64000) { fail(env, "Invalid bitrate"); return 0; }
    int error;
    auto* encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder || error != OPUS_OK) { fail(env, "Opus initialization failed"); return 0; }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_VBR(0)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK) {
        opus_encoder_destroy(encoder); fail(env, "Opus configuration failed"); return 0;
    }
    return reinterpret_cast<jlong>(encoder);
}
extern "C" JNIEXPORT jint JNICALL Java_com_daytrace_app_NativeAudio_lookahead(JNIEnv* env, jobject, jlong handle) {
    if (!handle) { fail(env, "Encoder closed"); return 0; }
    int samples = 0;
    if (opus_encoder_ctl(reinterpret_cast<OpusEncoder*>(handle), OPUS_GET_LOOKAHEAD(&samples)) != OPUS_OK) fail(env, "Opus lookahead failed");
    return samples;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_daytrace_app_NativeAudio_encode(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
    if (!handle) { fail(env, "Encoder closed"); return nullptr; }
    int16_t pcm[320]; if (!frame(env, bytes, pcm)) return nullptr;
    unsigned char packet[1276];
    int count = opus_encode(reinterpret_cast<OpusEncoder*>(handle), pcm, 320, packet, sizeof(packet));
    if (count < 0) { fail(env, opus_strerror(count)); return nullptr; }
    auto output = env->NewByteArray(count);
    if (output) env->SetByteArrayRegion(output, 0, count, reinterpret_cast<jbyte*>(packet));
    return output;
}
extern "C" JNIEXPORT void JNICALL Java_com_daytrace_app_NativeAudio_destroyEncoder(JNIEnv*, jobject, jlong handle) {
    if (handle) opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(handle));
}
extern "C" JNIEXPORT jlong JNICALL Java_com_daytrace_app_NativeAudio_createVad(JNIEnv* env, jobject, jint mode) {
    auto* vad = WebRtcVad_Create();
    if (!vad || WebRtcVad_Init(vad) != 0 || WebRtcVad_set_mode(vad, mode) != 0) {
        if (vad) WebRtcVad_Free(vad); fail(env, "WebRTC VAD initialization failed"); return 0;
    }
    return reinterpret_cast<jlong>(vad);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_daytrace_app_NativeAudio_isSpeech(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
    if (!handle) { fail(env, "VAD closed"); return false; }
    int16_t pcm[320]; if (!frame(env, bytes, pcm)) return false;
    int result = WebRtcVad_Process(reinterpret_cast<VadInst*>(handle), 16000, pcm, 320);
    if (result < 0) fail(env, "WebRTC VAD processing failed");
    return result == 1;
}
extern "C" JNIEXPORT void JNICALL Java_com_daytrace_app_NativeAudio_destroyVad(JNIEnv*, jobject, jlong handle) {
    if (handle) WebRtcVad_Free(reinterpret_cast<VadInst*>(handle));
}
