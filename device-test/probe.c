/* Test-only JNI function. No JNI API calls or headers are needed for this signature. */
__attribute__((visibility("default")))
int Java_dev_aqe_smoketest_NativeProbe_value(void *env, void *klass) {
    return PROBE_VALUE;
}
