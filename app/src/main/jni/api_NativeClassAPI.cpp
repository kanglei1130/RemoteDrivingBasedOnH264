/* DO NOT EDIT THIS FILE - it is machine generated */
#include <api_NativeClassAPI.h>
#include <assert.h>
#include <string.h>
#include <iostream>
#include "fec.h"
#include <android/log.h>


/*
 * Class:     api_NativeClassAPI
 * Method:    convertGray
 * Signature: (JJ)I
 */
JNIEXPORT jint JNICALL Java_api_NativeClassAPI_convertGray
  (JNIEnv *, jclass, jlong, jlong) {
  return 0;
  }

/*
 * Class:     api_NativeClassAPI
 * Method:    getSteeringAngle
 * Signature: ()D
 */
JNIEXPORT jdouble JNICALL Java_api_NativeClassAPI_getSteeringAngle
  (JNIEnv *, jclass) {
  return 0.0;
  }

/*
 * Class:     api_NativeClassAPI
 * Method:    getAcceleration
 * Signature: ()D
 */
JNIEXPORT jdouble JNICALL Java_api_NativeClassAPI_getAcceleration
  (JNIEnv *, jclass) {
  return 1.0;
  }

JNIEXPORT void JNICALL Java_api_NativeClassAPI_initFEC(JNIEnv *env, jobject thisObj) {
  __android_log_write(ANDROID_LOG_INFO, "JNI", "initFEC");
  FEClib::fec_init();
}

JNIEXPORT jbyteArray Java_api_NativeClassAPI_fecEncode(JNIEnv *env, jobject thisObj, jbyteArray data, jint blockSize, jint nrFecBlocks) {
  jsize lengthOfArray = env->GetArrayLength(data);
  jint n = lengthOfArray/blockSize;
  jint k = n - nrFecBlocks;
  assert (lengthOfArray%blockSize == 0);

  unsigned char* dataP = new unsigned char[blockSize * k];
  env->GetByteArrayRegion (data, 0, blockSize * k, reinterpret_cast<jbyte*>(dataP));
  unsigned char *data_blocks[k];
  for (int i = 0; i < k; ++i) {
    data_blocks[i] = new unsigned char[blockSize];
    memcpy(data_blocks[i], dataP + blockSize * i, blockSize);
  }
  unsigned char *fec_blocks[nrFecBlocks];
  for (int i = 0; i < nrFecBlocks; ++i) {
    fec_blocks[i] = new unsigned char[blockSize];
  }
  FEClib::fec_encode(blockSize, data_blocks, k, fec_blocks, nrFecBlocks);
  jbyteArray ret = env->NewByteArray(nrFecBlocks * blockSize);
  for (int i = 0; i < nrFecBlocks; ++i) {
    env->SetByteArrayRegion(ret, i * blockSize, blockSize, (jbyte*) (fec_blocks[i]));
  }

  for (int i = 0; i < k; ++i) {
    delete [] data_blocks[i];
  }
  for (int i = 0; i < nrFecBlocks; ++i) {
    delete [] fec_blocks[i];
  }
  delete [] dataP;
  return ret;
}
