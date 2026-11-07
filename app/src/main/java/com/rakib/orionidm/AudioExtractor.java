package com.rakib.orionidm;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Extracts the audio track of a downloaded video file into an .m4a file,
 * using only Android's media framework (no FFmpeg).
 *
 * Fast path: if the audio is already AAC, the samples are remuxed into an
 * MP4/M4A container untouched - no quality loss, very fast.
 * Fallback: decode to PCM and re-encode as AAC-LC 128 kbps.
 *
 * A literal .mp3 file is not possible with stock Android - the platform
 * has no MP3 encoder. M4A/AAC plays in every modern player.
 */
public class AudioExtractor {

    public static boolean extract(File video, File outM4a) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(video.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0 || fmt == null) { ex.release(); return false; }
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            boolean ok;
            if (mime != null && mime.contains("mp4a")) {
                ok = remux(ex, track, fmt, outM4a);
            } else {
                ex.release();
                ok = transcode(video, outM4a);
            }
            return ok && outM4a.exists() && outM4a.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------

    private static boolean remux(MediaExtractor ex, int track, MediaFormat fmt,
                                 File out) throws IOException {
        ex.selectTrack(track);
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int outTrack = muxer.addTrack(fmt);
        muxer.start();
        ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean ok = true;
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME;
            muxer.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
        muxer.stop();
        muxer.release();
        return ok;
    }

    // ---------------------------------------------------------------

    private static boolean transcode(File video, File out) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        try {
            ex.setDataSource(video.getAbsolutePath());
            int track = -1;
            MediaFormat srcFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; srcFmt = f; break; }
            }
            if (track < 0 || srcFmt == null) return false;
            ex.selectTrack(track);

            String srcMime = srcFmt.getString(MediaFormat.KEY_MIME);
            int sampleRate = srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = Math.min(2, srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));

            decoder = MediaCodec.createDecoderByType(srcMime);
            decoder.configure(srcFmt, null, null, 0);
            decoder.start();

            MediaFormat encFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;
            boolean muxStarted = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEOS = false, decoderEOS = false, encoderEOS = false;
            ByteBuffer pending = null;
            long pendingPts = 0;

            while (!encoderEOS) {
                // Feed decoder
                if (!inputEOS) {
                    int inIdx = decoder.dequeueInputBuffer(2000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int size = ex.readSampleData(inBuf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEOS = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size,
                                    ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                // Decoder output -> encoder input (handles chunking)
                if (!decoderEOS && pending == null) {
                    int outIdx = decoder.dequeueOutputBuffer(info, 2000);
                    if (outIdx >= 0) {
                        ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                        if (info.size > 0) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            pending = ByteBuffer.allocate(info.size);
                            pending.put(outBuf);
                            pending.flip();
                            pendingPts = info.presentationTimeUs;
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEOS = true;
                        }
                        decoder.releaseOutputBuffer(outIdx, false);
                    }
                }
                if (pending != null || decoderEOS) {
                    int encInIdx = encoder.dequeueInputBuffer(2000);
                    if (encInIdx >= 0) {
                        ByteBuffer encIn = encoder.getInputBuffer(encInIdx);
                        if (pending == null) {
                            encoder.queueInputBuffer(encInIdx, 0, 0, pendingPts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            int chunk = Math.min(pending.remaining(), encIn.capacity());
                            ByteBuffer slice = pending.duplicate();
                            slice.limit(slice.position() + chunk);
                            encIn.clear();
                            encIn.put(slice);
                            pending.position(pending.position() + chunk);
                            boolean last = !pending.hasRemaining();
                            encoder.queueInputBuffer(encInIdx, 0, chunk,
                                    pendingPts, 0);
                            if (last) pending = null;
                        }
                    }
                }
                // Encoder output -> muxer
                int encOutIdx = encoder.dequeueOutputBuffer(info, 2000);
                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        muxTrack = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxStarted = true;
                    }
                } else if (encOutIdx >= 0) {
                    ByteBuffer encOut = encoder.getOutputBuffer(encOutIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxStarted) {
                        encOut.position(info.offset);
                        encOut.limit(info.offset + info.size);
                        muxer.writeSampleData(muxTrack, encOut, info);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderEOS = true;
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false);
                }
            }
            return muxStarted && out.exists() && out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            ex.release();
        }
    }
}
