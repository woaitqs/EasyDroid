package com.qisen.android.easydroid.media;

/**
 * Created by tangqisen on 2017/7/8.
 */

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;
import android.widget.VideoView;

import java.io.IOException;
import java.util.Map;

public class SurfaceVideoView extends SurfaceView implements MediaPlayerControl {

  private static final String TAG = "SurfaceVideoView";

  // all possible internal states
  private static final int STATE_ERROR = -1;
  private static final int STATE_IDLE = 0;
  private static final int STATE_PREPARING = 1;
  private static final int STATE_PREPARED = 2;
  private static final int STATE_PLAYING = 3;
  private static final int STATE_PAUSED = 4;
  private static final int STATE_PLAYBACK_COMPLETED = 5;

  private Context mContext;

  // settable by the client
  private Uri mUri;
  private Map<String, String> mHeaders;

  // mCurrentState is a VideoView object's current state.
  // mTargetState is the state that a method caller intends to reach.
  // For instance, regardless the VideoView object's current state,
  // calling pause() intends to bring the object to a target state
  // of STATE_PAUSED.
  private int mCurrentState = STATE_IDLE;
  private int mTargetState = STATE_IDLE;

  // All the stuff we need for playing and showing a video
  private SurfaceHolder mSurfaceHolder = null;
  private MediaPlayer mMediaPlayer = null;
  private int mAudioSession;
  private int mVideoWidth;
  private int mVideoHeight;
  private int mSurfaceWidth;
  private int mSurfaceHeight;
  private MediaController mMediaController;
  private int mCurrentBufferPercentage;

  private OnCompletionListener mOnCompletionListener;
  private MediaPlayer.OnPreparedListener mOnPreparedListener;
  private OnErrorListener mOnErrorListener;
  private OnInfoListener mOnInfoListener;
  /* add by me */
  private MediaPlayer.OnSeekCompleteListener mOnSeekCompleteListener;
  private MediaPlayer.OnBufferingUpdateListener mOnBufferingUpdateListener;
  private MediaPlayer.OnVideoSizeChangedListener mOnVideoSizeChangedListener;
  /* add by me */

  private int mSeekWhenPrepared; // recording the seek position while preparing

  public SurfaceVideoView(Context context) {
    this(context, null);
  }

  public SurfaceVideoView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SurfaceVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    mContext = context;
    mVideoWidth = 0;
    mVideoHeight = 0;

    getHolder().addCallback(mSHCallback);
    getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

    setFocusable(true);
    setFocusableInTouchMode(true);
    requestFocus();

    mCurrentState = STATE_IDLE;
    mTargetState = STATE_IDLE;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    // Log.i("@@@@", "onMeasure(" + MeasureSpec.toString(widthMeasureSpec) + ", "
    // + MeasureSpec.toString(heightMeasureSpec) + ")");

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
          // Log.i("@@@", "image too wide, correcting");
          width = height * mVideoWidth / mVideoHeight;
        } else if (mVideoWidth * height > width * mVideoHeight) {
          // Log.i("@@@", "image too tall, correcting");
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
    } else {
      // no size yet, just adopt the given spec sizes
    }
    setMeasuredDimension(width, height);
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return VideoView.class.getName();
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
    mHeaders = headers;
    mSeekWhenPrepared = 0;
    openVideo();
    requestLayout();
    invalidate();
  }

  public void stopPlayback() {
    logD("call stop play back method.");
    if (mMediaPlayer != null) {
      mMediaPlayer.stop();
      mMediaPlayer.release();
      mMediaPlayer = null;
      mCurrentState = STATE_IDLE;
      mTargetState = STATE_IDLE;
      AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
      am.abandonAudioFocus(null);
    }
  }

  private void openVideo() {
    if (mUri == null || mSurfaceHolder == null) {
      // not ready for playback just yet, will try again later
      return;
    }
    // we shouldn't clear the target state, because somebody might have
    // called start() previously
    release(false);

    AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

    try {
      mMediaPlayer = new MediaPlayer();
      // TODO: create SubtitleController in MediaPlayer, but we need
      // a context for the subtitle renderers

      if (mAudioSession != 0) {
        mMediaPlayer.setAudioSessionId(mAudioSession);
      } else {
        mAudioSession = mMediaPlayer.getAudioSessionId();
      }
      mMediaPlayer.setOnPreparedListener(ownPreparedListener);
      mMediaPlayer.setOnVideoSizeChangedListener(ownSizeChangedListener);
      mMediaPlayer.setOnCompletionListener(ownCompletionListener);
      mMediaPlayer.setOnErrorListener(ownErrorListener);
      mMediaPlayer.setOnInfoListener(ownInfoListener);
      mMediaPlayer.setOnBufferingUpdateListener(ownBufferingUpdateListener);
      mCurrentBufferPercentage = 0;
      mMediaPlayer.setDataSource(mContext, mUri, mHeaders);
      mMediaPlayer.setDisplay(mSurfaceHolder);
      mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
      mMediaPlayer.setScreenOnWhilePlaying(true);

      mMediaPlayer.setOnSeekCompleteListener(ownSeekCompleteListener);

      mMediaPlayer.prepareAsync();

      // we don't set the target state here either, but preserve the
      // target state that was there before.
      mCurrentState = STATE_PREPARING;
      attachMediaController();
    } catch (IOException ex) {
      Log.w(TAG, "Unable to open content: " + mUri, ex);
      mCurrentState = STATE_ERROR;
      mTargetState = STATE_ERROR;
      ownErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
      return;
    } catch (IllegalArgumentException ex) {
      Log.w(TAG, "Unable to open content: " + mUri, ex);
      mCurrentState = STATE_ERROR;
      mTargetState = STATE_ERROR;
      ownErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
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

  MediaPlayer.OnVideoSizeChangedListener ownSizeChangedListener =
      new MediaPlayer.OnVideoSizeChangedListener() {
        public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
          mVideoWidth = mp.getVideoWidth();
          mVideoHeight = mp.getVideoHeight();
          // TODO 判断上层是否可以自行处理.
          // if (mVideoWidth != 0 && mVideoHeight != 0) {
          // getHolder().setFixedSize(mVideoWidth, mVideoHeight);
          // requestLayout();
          // }
          if (mOnVideoSizeChangedListener != null) {
            mOnVideoSizeChangedListener.onVideoSizeChanged(mp, width, height);
          }
        }
      };

  MediaPlayer.OnPreparedListener ownPreparedListener = new MediaPlayer.OnPreparedListener() {
    public void onPrepared(MediaPlayer mp) {
      logD("receive on Prepared method.");
      mCurrentState = STATE_PREPARED;

      if (mOnPreparedListener != null) {
        mOnPreparedListener.onPrepared(mMediaPlayer);
      }
      if (mMediaController != null) {
        mMediaController.setEnabled(true);
      }
      mVideoWidth = mp.getVideoWidth();
      mVideoHeight = mp.getVideoHeight();

      int seekToPosition = mSeekWhenPrepared; // mSeekWhenPrepared may be changed after seekTo()
                                              // call
      if (seekToPosition != 0) {
        seekTo(seekToPosition);
      }
      if (mVideoWidth != 0 && mVideoHeight != 0) {
        getHolder().setFixedSize(mVideoWidth, mVideoHeight);
        if (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight) {
          // We didn't actually change the size (it was already at the size
          // we need), so we won't get a "surface changed" callback, so
          // start the video here instead of in the callback.
          if (mTargetState == STATE_PLAYING) {
            if (mMediaController != null) {
              mMediaController.show();
            }
          } else if (!isPlaying() &&
              (seekToPosition != 0 || getCurrentPosition() > 0)) {
            if (mMediaController != null) {
              // Show the media controls when we're paused into a video and make 'em stick.
              mMediaController.show(0);
            }
          }
        }
      } else {
        // We don't know the video size yet, but should start anyway.
        // The video size might be reported to us later.
        if (mTargetState == STATE_PLAYING) {
          start();
        }
      }
    }
  };

  private MediaPlayer.OnCompletionListener ownCompletionListener =
      new MediaPlayer.OnCompletionListener() {
        public void onCompletion(MediaPlayer mp) {
          mCurrentState = STATE_PLAYBACK_COMPLETED;
          mTargetState = STATE_PLAYBACK_COMPLETED;
          if (mMediaController != null) {
            mMediaController.hide();
          }
          if (mOnCompletionListener != null) {
            mOnCompletionListener.onCompletion(mMediaPlayer);
          }
        }
      };

  private MediaPlayer.OnSeekCompleteListener ownSeekCompleteListener =
      new MediaPlayer.OnSeekCompleteListener() {
        @Override
        public void onSeekComplete(MediaPlayer mp) {
          if (mOnSeekCompleteListener != null) {
            mOnSeekCompleteListener.onSeekComplete(mp);
          }
        }
      };

  private MediaPlayer.OnInfoListener ownInfoListener =
      new MediaPlayer.OnInfoListener() {
        public boolean onInfo(MediaPlayer mp, int arg1, int arg2) {
          if (mOnInfoListener != null) {
            mOnInfoListener.onInfo(mp, arg1, arg2);
          }
          return true;
        }
      };

  private MediaPlayer.OnErrorListener ownErrorListener =
      new MediaPlayer.OnErrorListener() {
        public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
          Log.d(TAG, "Error: " + framework_err + "," + impl_err);
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

  private MediaPlayer.OnBufferingUpdateListener ownBufferingUpdateListener =
      new MediaPlayer.OnBufferingUpdateListener() {
        public void onBufferingUpdate(MediaPlayer mp, int percent) {
          mCurrentBufferPercentage = percent;
          if (mOnBufferingUpdateListener != null) {
            mOnBufferingUpdateListener.onBufferingUpdate(mp, percent);
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
   * or if the listener returned false, VideoView will inform
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

  public void setOnSeekCompleteListener(MediaPlayer.OnSeekCompleteListener listener) {
    mOnSeekCompleteListener = listener;
  }

  public void setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener listener) {
    mOnBufferingUpdateListener = listener;
  }

  public void setOnVideoSizeChangedListener(MediaPlayer.OnVideoSizeChangedListener listener) {
    mOnVideoSizeChangedListener = listener;
  }

  SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback() {
    public void surfaceChanged(SurfaceHolder holder, int format,
                               int w, int h) {
      mSurfaceWidth = w;
      mSurfaceHeight = h;
      boolean isValidState = (mTargetState == STATE_PLAYING);
      boolean hasValidSize = (mVideoWidth == w && mVideoHeight == h);
      if (mMediaPlayer != null && isValidState && hasValidSize) {
        if (mSeekWhenPrepared != 0) {
          seekTo(mSeekWhenPrepared);
        }
        start();
      }
    }

    public void surfaceCreated(SurfaceHolder holder) {
      mSurfaceHolder = holder;
      openVideo();
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
      // after we return from this we can't use the surface any more
      mSurfaceHolder = null;
      if (mMediaController != null) mMediaController.hide();
      release(true);
    }
  };

  /*
   * release the media player in any state
   */
  private void release(boolean cleartargetstate) {
    if (mMediaPlayer != null) {
      mMediaPlayer.reset();
      mMediaPlayer.release();
      mMediaPlayer = null;
      mCurrentState = STATE_IDLE;
      if (cleartargetstate) {
        mTargetState = STATE_IDLE;
      }
      AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
      am.abandonAudioFocus(null);
    }
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
    if (isInPlaybackState()) {
      mMediaPlayer.start();
      mCurrentState = STATE_PLAYING;
    }
    mTargetState = STATE_PLAYING;
  }

  @Override
  public void pause() {
    if (isInPlaybackState()) {
      if (mMediaPlayer.isPlaying()) {
        mMediaPlayer.pause();
        mCurrentState = STATE_PAUSED;
      }
    }
    mTargetState = STATE_PAUSED;
  }

  public void suspend() {
    release(false);
  }

  public void resume() {
    openVideo();
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
    return true;
  }

  @Override
  public boolean canSeekBackward() {
    return true;
  }

  @Override
  public boolean canSeekForward() {
    return true;
  }

  @Override
  public int getAudioSessionId() {
    if (mAudioSession == 0) {
      MediaPlayer foo = new MediaPlayer();
      mAudioSession = foo.getAudioSessionId();
      foo.release();
    }
    return mAudioSession;
  }

  private void logD(String message, Object... objects) {
    Log.d(TAG, String.format(message, objects));
  }

}
