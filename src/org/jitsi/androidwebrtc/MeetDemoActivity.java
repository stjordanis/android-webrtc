/*
 * libjingle
 * Copyright 2013, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jitsi.androidwebrtc;

import android.annotation.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.media.AudioManager;
import android.os.*;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main Activity of the AppRTCDemo Android app demonstrating interoperability
 * between the Android/Java implementation of PeerConnection and the
 * apprtc.appspot.com demo webapp.
 */
public class MeetDemoActivity
        extends Activity
        implements MeetClient.IceServersObserver
{
    private static final String TAG = "AppRTCDemoActivity";
    private static boolean factoryStaticInitialized;
    private PeerConnectionFactory factory;
    private VideoSource videoSource;
    private boolean videoSourceStopped;
    private PeerConnection pc;
    private final PCObserver pcObserver = new PCObserver();
    private final SDPObserver sdpObserver = new SDPObserver();
    private MeetClient appRtcClient = new MeetClient(this, this);
    private MeetRTCGLView vsv;
    private VideoRenderer.Callbacks localRender;

    private final static int MAX_REMOTE_COUNT = 15;

    private VideoStreamHandler[] remoteRenders
            = new VideoStreamHandler[MAX_REMOTE_COUNT];

    private Toast logToast;
    private final LayoutParams hudLayout =
            new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    private TextView hudView;
    // Synchronize on quit[0] to avoid teardown-related crashes.
    private final Boolean[] quit = new Boolean[]{false};
    private MediaConstraints sdpMediaConstraints;

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler(
            new UnhandledExceptionHandler(this));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Point displaySize = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(displaySize);

        vsv = new MeetRTCGLView(this, displaySize);
        VideoRendererGui.setView(vsv);

        localRender = VideoRendererGui.create(0, 0, 25, 25);

        remoteRenders[0] = new VideoStreamHandler(
                VideoRendererGui.create(25, 0, 25, 25));
        remoteRenders[1] = new VideoStreamHandler(
                VideoRendererGui.create(50, 0, 25, 25));
        remoteRenders[2] = new VideoStreamHandler(
                VideoRendererGui.create(75, 0, 25, 25));

        remoteRenders[3] = new VideoStreamHandler(
                VideoRendererGui.create(0, 25, 25, 25));
        remoteRenders[4] = new VideoStreamHandler(
                VideoRendererGui.create(25, 25, 25, 25));
        remoteRenders[5] = new VideoStreamHandler(
                VideoRendererGui.create(50, 25, 25, 25));
        remoteRenders[6] = new VideoStreamHandler(
                VideoRendererGui.create(75, 25, 25, 25));

        remoteRenders[7] = new VideoStreamHandler(
                VideoRendererGui.create(0, 50, 25, 25));
        remoteRenders[8] = new VideoStreamHandler(
                VideoRendererGui.create(25, 50, 25, 25));
        remoteRenders[9] = new VideoStreamHandler(
                VideoRendererGui.create(50, 50, 25, 25));
        remoteRenders[10] = new VideoStreamHandler(
                VideoRendererGui.create(75, 50, 25, 25));

        remoteRenders[11] = new VideoStreamHandler(
                VideoRendererGui.create(0, 75, 25, 25));
        remoteRenders[12] = new VideoStreamHandler(
                VideoRendererGui.create(25, 75, 25, 25));
        remoteRenders[13] = new VideoStreamHandler(
                VideoRendererGui.create(50, 75, 25, 25));
        remoteRenders[14] = new VideoStreamHandler(
                VideoRendererGui.create(75, 75, 25, 25));

        vsv.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                toggleHUD();
            }
        });
        setContentView(vsv);
        logAndToast("Tap the screen to toggle stats visibility");

        hudView = new TextView(this);
        hudView.setTextColor(Color.BLACK);
        hudView.setBackgroundColor(Color.WHITE);
        hudView.setAlpha(0.4f);
        hudView.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5);
        hudView.setVisibility(View.INVISIBLE);
        addContentView(hudView, hudLayout);

        if (!factoryStaticInitialized)
        {
            abortUnless(PeerConnectionFactory.initializeAndroidGlobals(
                            this, true, true),
                    "Failed to initializeAndroidGlobals");
            factoryStaticInitialized = true;
        }

        AudioManager audioManager =
                ((AudioManager) getSystemService(AUDIO_SERVICE));
        // TODO(fischman): figure out how to do this Right(tm) and remove the
        // suppression.
        @SuppressWarnings("deprecation")
        boolean isWiredHeadsetOn = audioManager.isWiredHeadsetOn();
        audioManager.setMode(isWiredHeadsetOn ?
                AudioManager.MODE_IN_CALL : AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(!isWiredHeadsetOn);

        sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo", "true"));

        final Intent intent = getIntent();
        if ("android.intent.action.VIEW".equals(intent.getAction()))
        {
            connectToRoom(intent.getData().toString());
            return;
        }
        showGetRoomUI();
    }

    private void showGetRoomUI()
    {
        final EditText roomInput = new EditText(this);
        roomInput.setText("https://pawel.jitsi.net/test");
        roomInput.setSelection(roomInput.getText().length());
        DialogInterface.OnClickListener listener =
                new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        abortUnless(which == DialogInterface.BUTTON_POSITIVE, "lolwat?");
                        dialog.dismiss();
                        connectToRoom(roomInput.getText().toString());
                    }
                };
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder
                .setMessage("Enter room URL").setView(roomInput)
                .setPositiveButton("Go!", listener).show();
    }

    private void connectToRoom(String roomUrl)
    {
        logAndToast("Connecting to room...");
        appRtcClient.connectToRoom(roomUrl);


    }

    // Toggle visibility of the heads-up display.
    private void toggleHUD()
    {
        if (hudView.getVisibility() == View.VISIBLE)
        {
            hudView.setVisibility(View.INVISIBLE);
        }
        else
        {
            hudView.setVisibility(View.VISIBLE);
        }
    }

    // Update the heads-up display with information from |reports|.
    private void updateHUD(StatsReport[] reports)
    {
        StringBuilder builder = new StringBuilder();
        for (StatsReport report : reports)
        {
            // bweforvideo to show statistics for video Bandwidth Estimation,
            // which is global per-session.
            if (report.id.equals("bweforvideo"))
            {
                for (StatsReport.Value value : report.values)
                {
                    String name = value.name.replace("goog", "")
                            .replace("Available", "").replace("Bandwidth", "")
                            .replace("Bitrate", "").replace("Enc", "");

                    builder.append(name).append("=").append(value.value)
                            .append(" ");
                }
                builder.append("\n");
            }
            else if (report.type.equals("googCandidatePair"))
            {
                String activeConnectionStats = getActiveConnectionStats(report);
                if (activeConnectionStats == null)
                {
                    continue;
                }
                builder.append(activeConnectionStats);
            }
            else
            {
                continue;
            }
            builder.append("\n");
        }
        hudView.setText(builder.toString() + hudView.getText());
    }

    // Return the active connection stats else return null
    private String getActiveConnectionStats(StatsReport report)
    {
        StringBuilder activeConnectionbuilder = new StringBuilder();
        // googCandidatePair to show information about the active
        // connection.
        for (StatsReport.Value value : report.values)
        {
            if (value.name.equals("googActiveConnection")
                    && value.value.equals("false"))
            {
                return null;
            }
            String name = value.name.replace("goog", "");
            activeConnectionbuilder.append(name).append("=")
                    .append(value.value).append("\n");
        }
        return activeConnectionbuilder.toString();
    }

    @Override
    public void onPause()
    {
        Log.d(TAG, "onPause");
        super.onPause();
        vsv.onPause();
        if (videoSource != null)
        {
            videoSource.stop();
            videoSourceStopped = true;
        }
    }

    @Override
    protected void onStop()
    {
        Log.d(TAG, "onStop");

        super.onStop();

        appRtcClient.disconnect();
    }

    @Override
    public void onResume()
    {
        Log.d(TAG, "onResume");
        super.onResume();
        vsv.onResume();
        if (videoSource != null && videoSourceStopped)
        {
            videoSource.restart();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        Log.d(TAG, "ON CONFIG CHANGED");

        Point displaySize = new Point();
        getWindowManager().getDefaultDisplay().getSize(displaySize);
        vsv.updateDisplaySize(displaySize);
        super.onConfigurationChanged(newConfig);
    }

    // Just for fun (and to regression-test bug 2302) make sure that DataChannels
    // can be created, queried, and disposed.
    private static void createDataChannelToRegressionTestBug2302(
            PeerConnection pc)
    {
        DataChannel dc = pc.createDataChannel("dcLabel", new DataChannel.Init());
        abortUnless("dcLabel".equals(dc.label()), "Unexpected label corruption?");
        dc.close();
        dc.dispose();
    }

    @Override
    public void onIceServers(List<PeerConnection.IceServer> iceServers)
    {
        factory = new PeerConnectionFactory();

        MediaConstraints pcConstraints = appRtcClient.pcConstraints();
        pcConstraints.optional.add(
                new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));
        pc = factory.createPeerConnection(iceServers, pcConstraints, pcObserver);

        createDataChannelToRegressionTestBug2302(pc);  // See method comment.

        // Uncomment to get ALL WebRTC tracing and SENSITIVE libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        //Logging.enableTracing(
        //    "logcat:",
        //    EnumSet.of(Logging.TraceLevel.TRACE_ALL),
        //    Logging.Severity.LS_SENSITIVE);

        {
            final PeerConnection finalPC = pc;
            final Runnable repeatedStatsLogger = new Runnable()
            {
                public void run()
                {
                    synchronized (quit[0])
                    {
                        if (quit[0])
                        {
                            return;
                        }
                        final Runnable runnableThis = this;
                        if (hudView.getVisibility() == View.INVISIBLE)
                        {
                            vsv.postDelayed(runnableThis, 1000);
                            return;
                        }
                        boolean success = finalPC.getStats(new StatsObserver()
                        {
                            public void onComplete(final StatsReport[] reports)
                            {
                                runOnUiThread(new Runnable()
                                {
                                    public void run()
                                    {
                                        updateHUD(reports);
                                    }
                                });
                                for (StatsReport report : reports)
                                {
                                    Log.d(TAG, "Stats: " + report.toString());
                                }
                                vsv.postDelayed(runnableThis, 1000);
                            }
                        }, null);
                        if (!success)
                        {
                            throw new RuntimeException("getStats() return false!");
                        }
                    }
                }
            };
            vsv.postDelayed(repeatedStatsLogger, 1000);
        }

        {
            logAndToast("Creating local video source...");
            if (appRtcClient.audioConstraints() != null)
            {
                MediaStream aMS = factory.createLocalMediaStream("AndroidAMS");
                aMS.addTrack(factory.createAudioTrack(
                        "AudioTrack",
                        factory.createAudioSource(appRtcClient.audioConstraints())));
                pc.addStream(aMS, new MediaConstraints());
            }


            if (appRtcClient.videoConstraints() != null)
            {
                MediaStream vMS = factory.createLocalMediaStream("AndroidVMS");
                VideoCapturer capturer = getVideoCapturer();
                videoSource = factory.createVideoSource(
                        capturer, appRtcClient.videoConstraints());
                VideoTrack videoTrack =
                        factory.createVideoTrack("VideoTrack", videoSource);
                videoTrack.addRenderer(new VideoRenderer(localRender));
                vMS.addTrack(videoTrack);

                pc.addStream(vMS, new MediaConstraints());
            }

        }
        logAndToast("Waiting for ICE candidates...");
    }

    // Cycle through likely device names for the camera and return the first
    // capturer that works, or crash if none do.
    private VideoCapturer getVideoCapturer()
    {
        String[] cameraFacing = {"front", "back"};
        int[] cameraIndex = {0, 1};
        int[] cameraOrientation = {0, 90, 180, 270};
        for (String facing : cameraFacing)
        {
            for (int index : cameraIndex)
            {
                for (int orientation : cameraOrientation)
                {
                    String name = "Camera " + index + ", Facing " + facing +
                            ", Orientation " + orientation;
                    VideoCapturer capturer = VideoCapturer.create(name);
                    if (capturer != null)
                    {
                        logAndToast("Using camera: " + name);
                        return capturer;
                    }
                }
            }
        }
        throw new RuntimeException("Failed to open capturer");
    }

    @Override
    protected void onDestroy()
    {
        disconnectAndExit();
        super.onDestroy();
    }

    // Poor-man's assert(): die with |msg| unless |condition| is true.
    private static void abortUnless(boolean condition, String msg)
    {
        if (!condition)
        {
            throw new RuntimeException(msg);
        }
    }

    // Log |msg| and Toast about it.
    private void logAndToast(String msg)
    {
        Log.d(TAG, msg);
        if (logToast != null)
        {
            logToast.cancel();
        }
        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        logToast.show();
    }

    // Put a |key|->|value| mapping in |json|.
    private static void jsonPut(JSONObject json, String key, Object value)
    {
        try
        {
            json.put(key, value);
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }
    }

    // Mangle SDP to prefer ISAC/16000 over any other audio codec.
    private static String preferISAC(String sdpDescription)
    {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String isac16kRtpMap = null;
        Pattern isac16kPattern =
                Pattern.compile("^a=rtpmap:(\\d+) ISAC/16000[\r]?$");
        for (int i = 0;
             (i < lines.length) && (mLineIndex == -1 || isac16kRtpMap == null);
             ++i)
        {
            if (lines[i].startsWith("m=audio "))
            {
                mLineIndex = i;
                continue;
            }
            Matcher isac16kMatcher = isac16kPattern.matcher(lines[i]);
            if (isac16kMatcher.matches())
            {
                isac16kRtpMap = isac16kMatcher.group(1);
                continue;
            }
        }
        if (mLineIndex == -1)
        {
            Log.d(TAG, "No m=audio line, so can't prefer iSAC");
            return sdpDescription;
        }
        if (isac16kRtpMap == null)
        {
            Log.d(TAG, "No ISAC/16000 line, so can't prefer iSAC");
            return sdpDescription;
        }
        String[] origMLineParts = lines[mLineIndex].split(" ");
        StringBuilder newMLine = new StringBuilder();
        int origPartIndex = 0;
        // Format is: m=<media> <port> <proto> <fmt> ...
        newMLine.append(origMLineParts[origPartIndex++]).append(" ");
        newMLine.append(origMLineParts[origPartIndex++]).append(" ");
        newMLine.append(origMLineParts[origPartIndex++]).append(" ");
        newMLine.append(isac16kRtpMap);
        for (; origPartIndex < origMLineParts.length; ++origPartIndex)
        {
            if (!origMLineParts[origPartIndex].equals(isac16kRtpMap))
            {
                newMLine.append(" ").append(origMLineParts[origPartIndex]);
            }
        }
        lines[mLineIndex] = newMLine.toString();
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines)
        {
            newSdpDescription.append(line).append("\r\n");
        }
        return newSdpDescription.toString();
    }

    public void setRemoteDescription(SessionDescription remoteDescription)
    {
        Log.i(TAG, "Set remote description: " + remoteDescription.description);
        pc.setRemoteDescription(sdpObserver, remoteDescription);
    }

    public SessionDescription getRemoteDescription()
    {
        return pc.getRemoteDescription();
    }

    // Implementation detail: observe ICE & stream changes and react accordingly.
    private class PCObserver
            implements PeerConnection.Observer
    {
        @Override
        public void onIceCandidate(final IceCandidate candidate)
        {
            Log.i(TAG, "ICE CANDIDATE!!! " + candidate);
            //FIXME here are ICE candidates to be sent as transport-info
            MeetDemoActivity.this.appRtcClient.participant.sendTransportInfo(candidate);
        }

        @Override
        public void onError()
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    throw new RuntimeException("PeerConnection error!");
                }
            });
        }

        @Override
        public void onSignalingChange(
                PeerConnection.SignalingState newState)
        {
            Log.i(TAG, "SIGNALING STATE: " + newState);
        }

        @Override
        public void onIceConnectionChange(
                PeerConnection.IceConnectionState newState)
        {
            Log.i(TAG, "ICE CONN CHANGE: " + newState);
        }

        @Override
        public void onIceGatheringChange(
                PeerConnection.IceGatheringState newState)
        {
            Log.i(TAG, "ICE Gather CHANGE: " + newState);
        }

        @Override
        public void onAddStream(final MediaStream stream)
        {
            Log.d(TAG, "ADD STREAM: " + stream);

            if ("mixedmslabel".equals(stream.label()))
            {
                Log.d(TAG, "Ignoring mixed stream");
                return;
            }
            if (stream.videoTracks.size() < 1)
            {
                return;
            }

            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    boolean found = false;
                    for (VideoStreamHandler handler : remoteRenders)
                    {
                        if (!handler.isRunning())
                        {
                            handler.start(stream);
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                    {
                        Log.d(TAG,
                                "Unable to assign renderer for " + stream
                                        + "all renders are busy.");
                    }
                }
            });
        }

        private VideoStreamHandler findRendererForStream(MediaStream ms)
        {
            for (VideoStreamHandler handler : remoteRenders)
            {
                if (handler.isStreamRenderer(ms))
                    return handler;
            }
            return null;
        }

        @Override
        public void onRemoveStream(final MediaStream stream)
        {
            Log.d(TAG, "REMOVE STREAM: " + stream);

            if (stream.videoTracks.size() < 1)
                return;

            final VideoStreamHandler handler
                    = findRendererForStream(stream);

            if (handler == null)
            {
                Log.e(TAG, "No video handler found for " + stream);
                return;
            }

            handler.stop();

            /*runOnUiThread(new Runnable()
            {
                public void run()
                {
                    //stream.videoTracks.get(0).dispose();


                    //stream.videoTracks.get(0).dispose();

                    //vsv.requestRender();
                }
            });*/
        }

        @Override
        public void onDataChannel(final DataChannel dc)
        {
            Log.i(TAG, "Data channel opened: " + dc);
        }

        @Override
        public void onRenegotiationNeeded()
        {
            // No need to do anything; AppRTC follows a pre-agreed-upon
            // signaling/negotiation protocol.
        }
    }

    // Implementation detail: handle offer creation/signaling and answer setting,
    // as well as adding remote ICE candidates once the answer SDP is set.
    private class SDPObserver
            implements SdpObserver
    {
        private SessionDescription localSdp;

        @Override
        public void onCreateSuccess(final SessionDescription origSdp)
        {

            Log.i(TAG,
                    "SDP create success type: " + origSdp.type.canonicalForm()
                            + ", desc:" + origSdp.description);

            final SessionDescription sdp = new SessionDescription(
                    origSdp.type, preferISAC(origSdp.description));

            final boolean sendAccept = localSdp == null;

            localSdp = sdp;

            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    pc.setLocalDescription(sdpObserver, sdp);

                    if (sendAccept)
                        appRtcClient.sendSessionAccept(sdp);
                }
            });
        }

        @Override
        public void onSetSuccess()
        {
            Log.i(TAG, "On SDP SET SUCCESS");

            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    if (pc.getLocalDescription() == null)
                    {
                        // We just set the remote offer, time to create our answer.
                        logAndToast("Creating answer");
                        Log.i(TAG, "createAnswer");
                        pc.createAnswer(SDPObserver.this, sdpMediaConstraints);
                    }
                }
            });
        }

        @Override
        public void onCreateFailure(final String error)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    throw new RuntimeException("createSDP error: " + error);
                }
            });
        }

        @Override
        public void onSetFailure(final String error)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    throw new RuntimeException("setSDP error: " + error);
                }
            });
        }
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnectAndExit()
    {
        synchronized (quit[0])
        {
            if (quit[0])
            {
                return;
            }
            quit[0] = true;
            if (pc != null)
            {
                pc.dispose();
                pc = null;
            }
            if (appRtcClient != null)
            {
                appRtcClient.disconnect();
                appRtcClient = null;
            }
            if (videoSource != null)
            {
                videoSource.dispose();
                videoSource = null;
            }
            if (factory != null)
            {
                factory.dispose();
                factory = null;
            }
            finish();
        }
    }
}