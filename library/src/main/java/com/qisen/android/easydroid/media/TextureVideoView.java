package com.qisen.android.easydroid.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;

import java.io.IOException;
import java.util.Map;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * 利用 TextureView 实现的系统播放核。
 *
 * ### 生命周期
 * 维持一个 MediaPlayer，这个 MediaPlayer 需要在 {@link #setVideoURI(Uri, Map)} 的时候，新建一个；
 * 同时在 {@link #stopPlayback()} 的时候，进行销毁，释放资源。同时相关的 {@link #mSurface}、
 * {@link #mSurfaceTexture} 也要跟随生命周期进行释放。
 *
 * ### 相关回调
 * 有些回调，在系统播放核中没有给出。这里做一些扩展，将其他接口也暴露出去。
 * 例如 {@link MediaPlayer#setOnVideoSizeChangedListener(MediaPlayer.OnVideoSizeChangedListener)}
 * 其中对于 {@link MediaPlayer#setOnInfoListener(OnInfoListener)} 做适配处理，使得其更具有可读性.
 *
 * ### 适配调整
 * 移除系统核中，一些自定义的处理，例如错误对话框。
 *
 * @author qisen.tqs@alibaba-inc.com
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class TextureVideoView extends TextureView implements MediaPlayerControl {

  private static final String TAG = "TextureVideoView";

  // all possible internal states
  private static final int STATE_ERROR = -1;
  private static final int STATE_IDLE = 0;
  private static final int STATE_PREPARING = 1;
  private static final int STATE_PREPARED = 2;
  private static final int STATE_PLAYING = 3;
  private static final int STATE_PAUSED = 4;
  private static final int STATE_PLAYBACK_COMPLETED = 5;

  // settable by the client
  private Uri mUri;
  private Map<String, String> mHeaders;

  // mCurrentState is a TextureVideoView object's current state.
  // mTargetState is the state that a method caller intends to reach.
  // For instance, regardless the TextureVideoView object's current state,
  // calling pause() intends to bring the object to a target state
  // of STATE_PAUSED.
  private int mCurrentState = STATE_IDLE;
  private int mTargetState = STATE_IDLE;

  // All the stuff we need for playing and showing a video.
  private Surface mSurface = null;
  // show the data of the mSurface to mSurfaceTexture.
  private SurfaceTexture mSurfaceTexture = null;
  // real media player to execute.
  private MediaPlayer mMediaPlayer = null;

  // media player listeners
  private OnCompletionListener mOnCompletionListener;
  private MediaPlayer.OnPreparedListener mOnPreparedListener;
  private OnErrorListener mOnErrorListener;
  private OnInfoListener mOnInfoListener;
  private MediaPlayer.OnSeekCompleteListener mOnSeekCompleteListener;
  private MediaPlayer.OnBufferingUpdateListener mOnBufferingUpdateListener;
  private MediaPlayer.OnVideoSizeChangedListener mOnVideoSizeChangedListener;

  private int mAudioSession;
  private int mVideoWidth;
  private int mVideoHeight;

  private int mCurrentBufferPercentage;
  // recording the seek position while preparing
  private int mSeekWhenPrepared;
  private boolean mCanPause;
  private boolean mCanSeekBack;
  private boolean mCanSeekForward;
  private boolean mShouldRequestAudioFocus = true;

  // optional
  private MediaController mMediaController;

  private TextureView.SurfaceTextureListener mSurfaceTextureListener =
      new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width,
                                                final int height) {
          // do nothing.
        }

        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture surfaceTexture, final int width,
                                              final int height) {
          logE("surface available");
          // 当 surface 已经准备好的时候，尝试去检查当前是否有视频需要播放。
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 6.0 和以上机型
            if (mSurfaceTexture == null) {
              mSurfaceTexture = surfaceTexture;
            } else {
              setSurfaceTexture(mSurfaceTexture);
            }
            mSurface = new Surface(mSurfaceTexture);
          } else {
            // 6.0 以下机型，有新的直接更新
            mSurfaceTexture = surfaceTexture;
            mSurface = new Surface(mSurfaceTexture);
            if (mMediaPlayer != null) {
              mMediaPlayer.setSurface(mSurface);
            }
          }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
          logE("surface destroyed");
          return mSurfaceTexture == null;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
          // do nothing.
        }
      };

  public TextureVideoView(Context context) {
    this(context, null);
  }

  public TextureVideoView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public TextureVideoView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    mVideoWidth = 0;
    mVideoHeight = 0;

    setSurfaceTextureListener(mSurfaceTextureListener);

    setFocusable(true);
    setFocusableInTouchMode(true);
    requestFocus();

    mCurrentState = STATE_IDLE;
    mTargetState = STATE_IDLE;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

    int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
    int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
    if (mVideoWidth > 0 && mVideoHeight > 0) {

      int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
      int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
      int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
      int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

      if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
        // the size is fixed
        width = widthSpecSize;
        height = heightSpecSize;

        // for compatibility, we adjust size based on aspect ratio
        if (mVideoWidth * height < width * mVideoHeight) {
          width = height * mVideoWidth / mVideoHeight;
        } else if (mVideoWidth * height > width * mVideoHeight) {
          height = width * mVideoHeight / mVideoWidth;
        }
      } else if (widthSpecMode == MeasureSpec.EXACTLY) {
        // only the width is fixed, adjust the height to match aspect ratio if possible
        width = widthSpecSize;
        height = width * mVideoHeight / mVideoWidth;
        if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
          // couldn't match aspect ratio within the constraints
          height = heightSpecSize;
        }
      } else if (heightSpecMode == MeasureSpec.EXACTLY) {
        // only the height is fixed, adjust the width to match aspect ratio if possible
        height = heightSpecSize;
        width = height * mVideoWidth / mVideoHeight;
        if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
          // couldn't match aspect ratio within the constraints
          width = widthSpecSize;
        }
      } else {
        // neither the width nor the height are fixed, try to use actual video size
        width = mVideoWidth;
        height = mVideoHeight;
        if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
          // too tall, decrease both width and height
          height = heightSpecSize;
          width = height * mVideoWidth / mVideoHeight;
        }
        if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
          // too wide, decrease both width and height
          width = widthSpecSize;
          height = width * mVideoHeight / mVideoWidth;
        }
      }
    }
    setMeasuredDimension(width, height);
  }

  @Override
  public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
    super.onInitializeAccessibilityEvent(event);
    event.setClassName(TextureVideoView.class.getName());
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName(TextureVideoView.class.getName());
  }

  public int resolveAdjustedSize(int desiredSize, int measureSpec) {
    return getDefaultSize(desiredSize, measureSpec);
  }

  /**
   * Sets video path.
   *
   * @param path the path of the video.
   */
  public void setVideoPath(String path) {
    setVideoURI(Uri.parse(path));
  }

  /**
   * Sets video URI.
   *
   * @param uri the URI of the video.
   */
  public void setVideoURI(Uri uri) {
    setVideoURI(uri, null);
  }

  /**
   * Sets video URI using specific headers.
   *
   * @param uri the URI of the video.
   * @param headers the headers for the URI request.
   *          Note that the cross domain redirection is allowed by default, but that can be
   *          changed with key/value pairs through the headers parameter with
   *          "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value
   *          to disallow or allow cross domain redirection.
   */
  public void setVideoURI(Uri uri, Map<String, String> headers) {
    mUri = uri;

    logE("Start to play video %s", mUri.toString());

    mHeaders = headers;
    mSeekWhenPrepared = 0;
    requestLayout();
    invalidate();

    if (mMediaPlayer != null) {
      // 重新播放前，重置MediaPlayer，以免遇到错误的播放情况.
      releasePlayerSource(true, false);
    }
    prepareMediaPlayer();
  }

  public void stopPlayback() {
    releasePlayerSource(true);
  }

  /**
   * Clears the surface texture by attaching a GL context and clearing it.
   * Code taken from <a href="http://stackoverflow.com/a/31582209">Hugo Gresse's answer on
   * stackoverflow.com</a>.
   */
  private void clearSurface() {
    if (mSurface == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
      return;
    }

    EGL10 egl = (EGL10) EGLContext.getEGL();
    EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
    egl.eglInitialize(display, null);

    int[] attribList = {
        EGL10.EGL_RED_SIZE, 8,
        EGL10.EGL_GREEN_SIZE, 8,
        EGL10.EGL_BLUE_SIZE, 8,
        EGL10.EGL_ALPHA_SIZE, 8,
        EGL10.EGL_RENDERABLE_TYPE, EGL10.EGL_WINDOW_BIT,
        EGL10.EGL_NONE, 0, // placeholder for recordable [@-3]
        EGL10.EGL_NONE
    };
    EGLConfig[] configs = new EGLConfig[1];
    int[] numConfigs = new int[1];
    egl.eglChooseConfig(display, attribList, configs, configs.length, numConfigs);
    EGLConfig config = configs[0];
    EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, new int[] {
        12440, 2, EGL10.EGL_NONE
    });
    EGLSurface eglSurface = egl.eglCreateWindowSurface(display, config, mSurface, new int[] {
        EGL10.EGL_NONE
    });

    egl.eglMakeCurrent(display, eglSurface, eglSurface, context);
    GLES20.glClearColor(0, 0, 0, 1);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    egl.eglSwapBuffers(display, eglSurface);
    egl.eglDestroySurface(display, eglSurface);
    egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
    egl.eglDestroyContext(display, context);
    egl.eglTerminate(display);

    if (mSurface != null) {
      mSurface.release();
      mSurface = null;
    }
  }

  private void prepareMediaPlayer() {
    logE("prepare player source");

    if (mUri == null || mSurface == null) {
      // not ready for playback just yet, will try again later
      return;
    }

    if (mShouldRequestAudioFocus) {
      AudioManager am = (AudioManager) getContext().getApplicationContext()
          .getSystemService(Context.AUDIO_SERVICE);
      am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    try {
      mMediaPlayer = new MediaPlayer();

      if (mAudioSession != 0) {
        mMediaPlayer.setAudioSessionId(mAudioSession);
      } else {
        mAudioSession = mMediaPlayer.getAudioSessionId();
      }

      mCurrentBufferPercentage = 0;

      mMediaPlayer.setOnPreparedListener(mPreparedListener);
      mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
      mMediaPlayer.setOnCompletionListener(mCompletionListener);
      mMediaPlayer.setOnErrorListener(mErrorListener);
      mMediaPlayer.setOnInfoListener(mInfoListener);
      mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
      mMediaPlayer.setOnSeekCompleteListener(mSeekCompleteListener);
      mMediaPlayer.setDataSource(getContext().getApplicationContext(), mUri, mHeaders);
      mMediaPlayer.setSurface(mSurface);
      mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
      mMediaPlayer.setScreenOnWhilePlaying(true);
      mMediaPlayer.prepareAsync();

      // we don't set the target state here either, but preserve the
      // target state that was there before.
      mCurrentState = STATE_PREPARING;
      attachMediaController();
    } catch (IllegalArgumentException | IOException ex) {
      Log.w(TAG, "Unable to open content: " + mUri, ex);
      mCurrentState = STATE_ERROR;
      mTargetState = STATE_ERROR;
      mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
    }
  }

  public void setMediaController(MediaController controller) {
    if (mMediaController != null) {
      mMediaController.hide();
    }
    mMediaController = controller;
    attachMediaController();
  }

  private void attachMediaController() {
    if (mMediaPlayer != null && mMediaController != null) {
      mMediaController.setMediaPlayer(this);
      View anchorView = this.getParent() instanceof View ? (View) this.getParent() : this;
      mMediaController.setAnchorView(anchorView);
      mMediaController.setEnabled(isInPlaybackState());
    }
  }

  MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener =
      new MediaPlayer.OnVideoSizeChangedListener() {
        public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
          mVideoWidth = mp.getVideoWidth();
          mVideoHeight = mp.getVideoHeight();
          if (mVideoWidth != 0 && mVideoHeight != 0 && getSurfaceTexture() != null) {
            getSurfaceTexture().setDefaultBufferSize(mVideoWidth, mVideoHeight);
            requestLayout();
          }
          if (mOnVideoSizeChangedListener != null) {
            mOnVideoSizeChangedListener.onVideoSizeChanged(mp, width, height);
          }
        }
      };

  MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
    public void onPrepared(MediaPlayer mp) {
      mCurrentState = STATE_PREPARED;

      mCanPause = mCanSeekBack = mCanSeekForward = true;

      if (mOnPreparedListener != null) {
        mOnPreparedListener.onPrepared(mMediaPlayer);
      }
      if (mMediaController != null) {
        mMediaController.setEnabled(true);
      }
      mVideoWidth = mp.getVideoWidth();
      mVideoHeight = mp.getVideoHeight();

      if (mVideoWidth != 0 && mVideoHeight != 0) {
        if (getSurfaceTexture() == null) {
          return;
        }
        getSurfaceTexture().setDefaultBufferSize(mVideoWidth, mVideoHeight);
        // We won't get a "surface changed" callback if the surface is already the right size, so
        // start the video here instead of in the callback.
        if (mTargetState == STATE_PLAYING) {
          mp.start();
          if (mMediaController != null) {
            mMediaController.show();
          }
        } else if (!isPlaying() && getCurrentPosition() > 0) {
          if (mMediaController != null) {
            // Show the media controls when we're paused into a video and make 'em stick.
            mMediaController.show(0);
          }
        }
      } else {
        // We don't know the video size yet, but should start anyway.
        // The video size might be reported to us later.
        if (mTargetState == STATE_PLAYING) {
          mp.start();
        }
      }
    }
  };

  private MediaPlayer.OnCompletionListener mCompletionListener =
      new MediaPlayer.OnCompletionListener() {
        public void onCompletion(MediaPlayer mp) {
          mCurrentState = STATE_PLAYBACK_COMPLETED;
          mTargetState = STATE_PLAYBACK_COMPLETED;
          if (mMediaController != null) {
            mMediaController.hide();
          }
          // 系统核，有一定概率会出现没有网，继续播放的情况下
          // 在播放完成缓存部分后，就回调 onCompletion 的错误情况
          // 因而这里进行了容错处理.
          // 期待对系统核进行更换，替换上 IJK Player 的时候.
          if (getCurrentPosition() < mMediaPlayer.getDuration() * 0.95F) {
            if (mOnErrorListener != null) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                mOnErrorListener.onError(mp, MediaPlayer.MEDIA_ERROR_SERVER_DIED,
                    MediaPlayer.MEDIA_ERROR_IO);
              }
            }
          } else {
            if (mOnCompletionListener != null) {
              mOnCompletionListener.onCompletion(mMediaPlayer);
            }
          }
        }
      };

  private MediaPlayer.OnInfoListener mInfoListener =
      new MediaPlayer.OnInfoListener() {
        public boolean onInfo(MediaPlayer mp, int arg1, int arg2) {
          if (mOnInfoListener != null) {
            mOnInfoListener.onInfo(mp, arg1, arg2);
          }
          return true;
        }
      };

  private MediaPlayer.OnErrorListener mErrorListener =
      new MediaPlayer.OnErrorListener() {
        public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
          logE("Error: " + framework_err + "," + impl_err + ", mUri is %s", mUri.toString());
          mCurrentState = STATE_ERROR;
          mTargetState = STATE_ERROR;
          if (mMediaController != null) {
            mMediaController.hide();
          }

          /* If an error handler has been supplied, use it and finish. */
          if (mOnErrorListener != null) {
            if (mOnErrorListener.onError(mMediaPlayer, framework_err, impl_err)) {
              return true;
            }
          }

          return true;
        }
      };

  private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener =
      new MediaPlayer.OnBufferingUpdateListener() {
        public void onBufferingUpdate(MediaPlayer mp, int percent) {
          mCurrentBufferPercentage = percent;
          if (mOnBufferingUpdateListener != null) {
            mOnBufferingUpdateListener.onBufferingUpdate(mp, percent);
          }
        }
      };

  private MediaPlayer.OnSeekCompleteListener mSeekCompleteListener =
      new MediaPlayer.OnSeekCompleteListener() {
        @Override
        public void onSeekComplete(MediaPlayer mp) {
          if (mOnSeekCompleteListener != null) {
            mOnSeekCompleteListener.onSeekComplete(mp);
          }
        }
      };

  /**
   * Register a callback to be invoked when the media file
   * is loaded and ready to go.
   *
   * @param l The callback that will be run
   */
  public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
    mOnPreparedListener = l;
  }

  /**
   * Register a callback to be invoked when the end of a media file
   * has been reached during playback.
   *
   * @param l The callback that will be run
   */
  public void setOnCompletionListener(OnCompletionListener l) {
    mOnCompletionListener = l;
  }

  /**
   * Register a callback to be invoked when an error occurs
   * during playback or setup. If no listener is specified,
   * or if the listener returned false, TextureVideoView will inform
   * the user of any errors.
   *
   * @param l The callback that will be run
   */
  public void setOnErrorListener(OnErrorListener l) {
    mOnErrorListener = l;
  }

  /**
   * Register a callback to be invoked when an informational event
   * occurs during playback or setup.
   *
   * @param l The callback that will be run
   */
  public void setOnInfoListener(OnInfoListener l) {
    mOnInfoListener = l;
  }

  public void setOnSeekCompleteListener(MediaPlayer.OnSeekCompleteListener l) {
    mOnSeekCompleteListener = l;
  }

  public void setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener l) {
    mOnBufferingUpdateListener = l;
  }

  public void setOnVideoSizeChangedListener(MediaPlayer.OnVideoSizeChangedListener l) {
    mOnVideoSizeChangedListener = l;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (isInPlaybackState() && mMediaController != null) {
      toggleMediaControlsVisibility();
    }
    return false;
  }

  @Override
  public boolean onTrackballEvent(MotionEvent ev) {
    if (isInPlaybackState() && mMediaController != null) {
      toggleMediaControlsVisibility();
    }
    return false;
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
        keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
        keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
        keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
        keyCode != KeyEvent.KEYCODE_MENU &&
        keyCode != KeyEvent.KEYCODE_CALL &&
        keyCode != KeyEvent.KEYCODE_ENDCALL;
    if (isInPlaybackState() && isKeyCodeSupported && mMediaController != null) {
      if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
          keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        if (mMediaPlayer.isPlaying()) {
          pause();
          mMediaController.show();
        } else {
          start();
          mMediaController.hide();
        }
        return true;
      } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
        if (!mMediaPlayer.isPlaying()) {
          start();
          mMediaController.hide();
        }
        return true;
      } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
          || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
        if (mMediaPlayer.isPlaying()) {
          pause();
          mMediaController.show();
        }
        return true;
      } else {
        toggleMediaControlsVisibility();
      }
    }

    return super.onKeyDown(keyCode, event);
  }

  private void toggleMediaControlsVisibility() {
    if (mMediaController.isShowing()) {
      mMediaController.hide();
    } else {
      mMediaController.show();
    }
  }

  @Override
  public void start() {
    logE("media player start");
    if (isInPlaybackState()) {
      mMediaPlayer.start();
      mCurrentState = STATE_PLAYING;
    }
    mTargetState = STATE_PLAYING;
  }

  @Override
  public void pause() {
    logE("media player pause");
    if (isInPlaybackState()) {
      if (mMediaPlayer.isPlaying()) {
        mMediaPlayer.pause();
        mCurrentState = STATE_PAUSED;
      }
    }
    mTargetState = STATE_PAUSED;
  }

  public void suspend() {
    releasePlayerSource(true, false);
  }

  public void resume() {
    prepareMediaPlayer();
  }

  @Override
  public int getDuration() {
    if (isInPlaybackState()) {
      return mMediaPlayer.getDuration();
    }

    return -1;
  }

  @Override
  public int getCurrentPosition() {
    if (isInPlaybackState()) {
      return mMediaPlayer.getCurrentPosition();
    }
    return 0;
  }

  @Override
  public void seekTo(int msec) {
    if (isInPlaybackState()) {
      mMediaPlayer.seekTo(msec);
      mSeekWhenPrepared = 0;
    } else {
      mSeekWhenPrepared = msec;
    }
  }

  @Override
  public boolean isPlaying() {
    return isInPlaybackState() && mMediaPlayer.isPlaying();
  }

  @Override
  public int getBufferPercentage() {
    if (mMediaPlayer != null) {
      return mCurrentBufferPercentage;
    }
    return 0;
  }

  private boolean isInPlaybackState() {
    return (mMediaPlayer != null &&
        mCurrentState != STATE_ERROR &&
        mCurrentState != STATE_IDLE &&
        mCurrentState != STATE_PREPARING);
  }

  @Override
  public boolean canPause() {
    return mCanPause;
  }

  @Override
  public boolean canSeekBackward() {
    return mCanSeekBack;
  }

  @Override
  public boolean canSeekForward() {
    return mCanSeekForward;
  }

  public int getAudioSessionId() {
    if (mAudioSession == 0) {
      MediaPlayer foo = new MediaPlayer();
      mAudioSession = foo.getAudioSessionId();
      foo.release();
    }
    return mAudioSession;
  }

  /**
   * Sets the request audio focus flag. If enabled, {@link TextureVideoView} will request
   * audio focus when opening a video by calling {@link AudioManager}. This flag
   * should be set before calling {@link TextureVideoView#setVideoPath(String)} or
   * {@link TextureVideoView#setVideoURI(Uri)}. By default, {@link TextureVideoView} will
   * request audio focus.
   *
   * @param shouldRequestAudioFocus If {@code true}, {@link TextureVideoView} will request
   *          audio focus before opening a video, else audio focus is not requested
   */
  public void setShouldRequestAudioFocus(boolean shouldRequestAudioFocus) {
    mShouldRequestAudioFocus = shouldRequestAudioFocus;
  }

  /**
   * Returns the current state of the audio focus request flag.
   *
   * @return {@code true}, if {@link TextureVideoView} will request
   *         audio focus before opening a video, else {@code false}
   */
  public boolean shouldRequestAudioFocus() {
    return mShouldRequestAudioFocus;
  }

  /*
   * 释放当前任务相关的资源，这是一个播放任务生命周期的结束。
   * release the media player in any state
   */
  private void releasePlayerSource(boolean clearTargetState) {
    releasePlayerSource(clearTargetState, true);
  }

  private void releasePlayerSource(boolean clearTargetState, boolean destroySurface) {
    logE("release player source\n");
    if (mMediaPlayer != null) {
      mMediaPlayer.reset();
      mMediaPlayer.release();
      mMediaPlayer = null;
      mCurrentState = STATE_IDLE;
      if (clearTargetState) {
        mTargetState = STATE_IDLE;
      }
      if (mShouldRequestAudioFocus) {
        AudioManager am = (AudioManager) getContext().getApplicationContext()
            .getSystemService(Context.AUDIO_SERVICE);
        am.abandonAudioFocus(null);
      }
    }
    if (destroySurface) {
      if (mSurface != null) {
        mSurface.release();
        mSurface = null;
      }
      if (mSurfaceTexture != null) {
        mSurfaceTexture.release();
        mSurfaceTexture = null;
      }
    }
  }

  private void logE(String message, Object... objects) {
    Log.e(TAG, String.format(message, objects));
  }

}
