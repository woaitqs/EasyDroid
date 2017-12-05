package com.qisen.android.easydroid.permission;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * @author qisen.tqs@alibaba-inc.com
 *         Created on 2017/11/29.
 */

public class AudioPermissionCheckerPre21 implements PermissionChecker {

  static boolean askForAudioPermission() {
    // 音频获取源
    int audioSource = MediaRecorder.AudioSource.MIC;
    // 设置音频采样率，44100是目前的标准，但是某些设备仍然支持22050，16000，11025
    int sampleRateInHz = 44100;
    // 设置音频的录制的声道CHANNEL_IN_STEREO为双声道，CHANNEL_CONFIGURATION_MONO为单声道
    int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
    // 音频数据格式:PCM 16位每个样本。保证设备支持。PCM 8位每个样本。不一定能得到设备支持。
    int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    int bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRateInHz,
        channelConfig, audioFormat);
    AudioRecord audioRecord = new AudioRecord(audioSource, sampleRateInHz,
        channelConfig, audioFormat, bufferSizeInBytes);

    try {
      audioRecord.startRecording();
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING
        && audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
      return false;
    }

    byte[] bytes = new byte[1024];
    int readSize = audioRecord.read(bytes, 0, 1024);
    if (readSize == AudioRecord.ERROR_INVALID_OPERATION || readSize <= 0) {
      return false;
    }
    audioRecord.stop();
    audioRecord.release();
    return true;
  }

  @Override
  public void check(PermissionCallback callback) {
    boolean hasPermission = askForAudioPermission();
    callback.onPermissionResult(PermissionUtils.PermissionType.AUDIO, hasPermission);
  }
}
