#include <jni.h>
#include "role-profiler.h"
extern "C" JNIEXPORT jboolean JNICALL Java_ch_og_dictation_NativeRoleProfiler_start(JNIEnv *, jobject, jboolean enabled) {
  return dictation_profile::Start(enabled);
}
extern "C" JNIEXPORT jlong JNICALL Java_ch_og_dictation_NativeRoleProfiler_clockNs(JNIEnv *, jobject) {
  return dictation_profile::ClockNs();
}
extern "C" JNIEXPORT jboolean JNICALL Java_ch_og_dictation_NativeRoleProfiler_finish(JNIEnv *env, jobject, jstring path) {
  const char *p = env->GetStringUTFChars(path, nullptr);
  if (!p) return false;
  bool result = dictation_profile::Finish(p);
  env->ReleaseStringUTFChars(path, p);
  return result;
}
