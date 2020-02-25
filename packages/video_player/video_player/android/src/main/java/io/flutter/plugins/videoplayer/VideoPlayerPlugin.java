// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.graphics.Point;
import android.util.Log;
import android.util.LongSparseArray;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterMain;
import io.flutter.view.TextureRegistry;
import com.mux.stats.sdk.core.model.CustomerPlayerData;
import com.mux.stats.sdk.core.model.CustomerVideoData;
import com.mux.stats.sdk.muxstats.MuxStatsExoPlayer;

/** Android platform implementation of the VideoPlayerPlugin. */
public class VideoPlayerPlugin implements MethodCallHandler, FlutterPlugin {
  private static final String TAG = "VideoPlayerPlugin";
  private final LongSparseArray<VideoPlayer> videoPlayers = new LongSparseArray<>();
  private FlutterState flutterState;
  private MuxStatsExoPlayer muxStatsExoPlayer;
  private String videoSource;

  /** Register this with the v2 embedding for the plugin to respond to lifecycle callbacks. */
  public VideoPlayerPlugin() {}

  private VideoPlayerPlugin(Registrar registrar) {
    this.flutterState =
        new FlutterState(
            registrar.context(),
            registrar.messenger(),
            registrar::lookupKeyForAsset,
            registrar::lookupKeyForAsset,
            registrar.textures());
    flutterState.startListening(this);
  }

  /** Registers this with the stable v1 embedding. Will not respond to lifecycle events. */
  public static void registerWith(Registrar registrar) {
    final VideoPlayerPlugin plugin = new VideoPlayerPlugin(registrar);
    registrar.addViewDestroyListener(
        view -> {
          plugin.onDestroy();
          return false; // We are not interested in assuming ownership of the NativeView.
        });
  }

  @Override
  public void onAttachedToEngine(FlutterPluginBinding binding) {
    this.flutterState =
        new FlutterState(
            binding.getApplicationContext(),
            binding.getFlutterEngine().getDartExecutor(),
            FlutterMain::getLookupKeyForAsset,
            FlutterMain::getLookupKeyForAsset,
            binding.getFlutterEngine().getRenderer());
    flutterState.startListening(this);
  }

  @Override
  public void onDetachedFromEngine(FlutterPluginBinding binding) {
    if (flutterState == null) {
      Log.wtf(TAG, "Detached from the engine before registering to it.");
    }
    flutterState.stopListening();
    flutterState = null;
  }

  private void disposeAllPlayers() {
    for (int i = 0; i < videoPlayers.size(); i++) {
      videoPlayers.valueAt(i).dispose();
    }
    videoPlayers.clear();
  }

  private void onDestroy() {
    if (muxStatsExoPlayer != null) {
      muxStatsExoPlayer.release();
    }

    // The whole FlutterView is being destroyed. Here we release resources acquired for all
    // instances
    // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
    // be replaced with just asserting that videoPlayers.isEmpty().
    // https://github.com/flutter/flutter/issues/20989 tracks this.
    disposeAllPlayers();
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    if (flutterState == null || flutterState.textureRegistry == null) {
      result.error("no_activity", "video_player plugin requires a foreground activity", null);
      return;
    }
    switch (call.method) {
      case "init":
        disposeAllPlayers();
        break;
      case "create":
        {
          TextureRegistry.SurfaceTextureEntry handle =
              flutterState.textureRegistry.createSurfaceTexture();
          EventChannel eventChannel =
              new EventChannel(
                  flutterState.binaryMessenger, "flutter.io/videoPlayer/videoEvents" + handle.id());

          VideoPlayer player;
          if (call.argument("asset") != null) {
            String assetLookupKey;
            if (call.argument("package") != null) {
              assetLookupKey =
                  flutterState.keyForAssetAndPackageName.get(
                      call.argument("asset"), call.argument("package"));
            } else {
              assetLookupKey = flutterState.keyForAsset.get(call.argument("asset"));
            }
            videoSource = "asset:///" + assetLookupKey;
            player =
                new VideoPlayer(
                    flutterState.applicationContext,
                    eventChannel,
                    handle,
                    "asset:///" + assetLookupKey,
                    result,
                    null);
            videoPlayers.put(handle.id(), player);
          } else {
            videoSource = call.argument("uri");
            player =
                new VideoPlayer(
                    flutterState.applicationContext,
                    eventChannel,
                    handle,
                    call.argument("uri"),
                    result,
                    call.argument("formatHint"));
            videoPlayers.put(handle.id(), player);
          }
          break;
        }
      default:
        {
          long textureId = ((Number) call.argument("textureId")).longValue();
          VideoPlayer player = videoPlayers.get(textureId);
          if (player == null) {
            result.error(
                "Unknown textureId",
                "No video player associated with texture id " + textureId,
                null);
            return;
          }
          onMethodCall(call, result, textureId, player);
          break;
        }
    }
  }

  private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
    switch (call.method) {
      case "setLooping":
        player.setLooping(call.argument("looping"));
        result.success(null);
        break;
      case "setVolume":
        player.setVolume(call.argument("volume"));
        result.success(null);
        break;
      case "play":
        player.play();
        result.success(null);
        break;
      case "pause":
        player.pause();
        result.success(null);
        break;
      case "seekTo":
        int location = ((Number) call.argument("location")).intValue();
        player.seekTo(location);
        result.success(null);
        break;
      case "position":
        result.success(player.getPosition());
        player.sendBufferingUpdate();
        break;
      case "dispose":
        player.dispose();
        videoPlayers.remove(textureId);
        result.success(null);
        break;

      case "setupMux":
        CustomerPlayerData playerData = new CustomerPlayerData();
        playerData.setEnvironmentKey(call.argument("envKey"));
        playerData.setPlayerName(call.argument("playerName"));
        playerData.setViewerUserId(call.argument("viewerUserId"));
        playerData.setExperimentName(call.argument("experimentName"));
        playerData.setPlayerVersion(call.argument("playerVersion"));
        playerData.setPageType(call.argument("pageType"));
        playerData.setSubPropertyId(call.argument("subPropertyId"));
        playerData.setPlayerInitTime(call.argument("playerInitTime"));

        CustomerVideoData videoData = new CustomerVideoData();
        videoData.setVideoSourceUrl(videoSource);
        videoData.setVideoId(call.argument("videoId"));
        videoData.setVideoTitle(call.argument("videoTitle"));
        videoData.setVideoSeries(call.argument("videoSeries"));
        videoData.setVideoVariantName(call.argument("videoVariantName"));
        videoData.setVideoVariantId(call.argument("videoVariantId"));
        videoData.setVideoLanguageCode(call.argument("videoLanguageCode"));
        videoData.setVideoContentType(call.argument("videoContentType"));
        videoData.setVideoStreamType(call.argument("videoStreamType"));
        videoData.setVideoProducer(call.argument("videoProducer"));
        videoData.setVideoEncodingVariant(call.argument("videoEncodingVariant"));
        videoData.setVideoCdn(call.argument("videoCdn"));
        videoData.setVideoDuration(call.argument("videoDuration"));

        muxStatsExoPlayer = new MuxStatsExoPlayer(
          flutterState.applicationContext,
          player.exoPlayer,
          call.argument("playerName"),
          playerData,
          videoData
        );
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private interface KeyForAssetFn {
    String get(String asset);
  }

  private interface KeyForAssetAndPackageName {
    String get(String asset, String packageName);
  }

  private static final class FlutterState {
    private final Context applicationContext;
    private final BinaryMessenger binaryMessenger;
    private final KeyForAssetFn keyForAsset;
    private final KeyForAssetAndPackageName keyForAssetAndPackageName;
    private final TextureRegistry textureRegistry;
    private final MethodChannel methodChannel;

    FlutterState(
        Context applicationContext,
        BinaryMessenger messenger,
        KeyForAssetFn keyForAsset,
        KeyForAssetAndPackageName keyForAssetAndPackageName,
        TextureRegistry textureRegistry) {
      this.applicationContext = applicationContext;
      this.binaryMessenger = messenger;
      this.keyForAsset = keyForAsset;
      this.keyForAssetAndPackageName = keyForAssetAndPackageName;
      this.textureRegistry = textureRegistry;
      methodChannel = new MethodChannel(messenger, "flutter.io/videoPlayer");
    }

    void startListening(VideoPlayerPlugin methodCallHandler) {
      methodChannel.setMethodCallHandler(methodCallHandler);
    }

    void stopListening() {
      methodChannel.setMethodCallHandler(null);
    }
  }
}
