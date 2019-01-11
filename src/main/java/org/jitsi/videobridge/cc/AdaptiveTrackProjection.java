/*
 * Copyright @ 2019 8x8, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.cc;

import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.cc.vp8.*;

import java.lang.ref.*;

/**
 * Filters the packets coming from a specific {@link MediaStreamTrackDesc}
 * based on the currently forwarded subjective quality index. It's also taking
 * care of upscaling and downscaling. It is also responsible for rewriting the
 * forwarded packets so that the the quality switches are transparent from the
 * receiver. See svc.md in the doc folder for more details.
 *
 * Instances of this class are thread-safe.
 *
 * @author George Politis
 */
public class AdaptiveTrackProjection
{
    /**
     * The <tt>Logger</tt> used by the <tt>AdaptiveTrackProjection</tt> class
     * and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(AdaptiveTrackProjection.class);

    /**
     * An empty {@link RawPacket} array that is used as a return value when no
     * packets need to be piggy-backed.
     */
    public static final RawPacket[]
        EMPTY_PACKET_ARR = AdaptiveTrackProjectionContext.EMPTY_PACKET_ARR;

    /**
     * A {@link WeakReference} to the {@link MediaStreamTrackDesc} that owns
     * the packets that this instance filters.
     *
     * Note that we keep a {@link WeakReference} instead of a reference to allow
     * the channel/stream/etc objects to be de-allocated in case the sending
     * participant leaves the conference.
     */
    private final WeakReference<MediaStreamTrackDesc> weakSource;

    /**
     * The main SSRC of the source track (if simulcast is used, this is the SSRC
     * of the low-quality layer). We use it as the SSRC of the track projection
     * and also request keyframes from this SSRC.
     */
    private final long targetSsrc;

    /**
     * The payload specific track projection context that's responsible for
     * rewriting the packets of a projected track.
     *
     * XXX The more general scheme and more correct approach would be to have
     * the adaptive track projection manage a context per payload type. The
     * intention was to implement the more general approach but, at the time of
     * this righting, we have no use case for that right now nor an easy way to
     * test this dynamic payload type changes. So practically what happens is
     * that we create the context once, and it remains the same throughout the
     * life of the this instance. It's not really possible to simplify and make
     * the context final (=> create it during construction) because we need a
     * payload type (could be VP9, could be H264, could be VP8) so it has to be
     * created on packet arrival.
     */
    private AdaptiveTrackProjectionContext context;

    /**
     * The payload type that was used to determine the {@link #context} type.
     */
    private int contextPayloadType = -1;

    /**
     * The ideal quality index for this track projection.
     */
    private int idealIndex = RTPEncodingDesc.SUSPENDED_INDEX;

    /**
     * The target quality index for this track projection.
     */
    private int targetIndex = RTPEncodingDesc.SUSPENDED_INDEX;

    /**
     * Ctor.
     *
     * @param source the {@link MediaStreamTrackDesc} that owns the packets
     * that this instance filters.
     */
    AdaptiveTrackProjection(@NotNull MediaStreamTrackDesc source)
    {
        weakSource = new WeakReference<>(source);
        targetSsrc = source.getRTPEncodings()[0].getPrimarySSRC();
    }

    /**
     * @return the {@link MediaStreamTrackDesc} that owns the packets that this
     * instance filters. Note that this may return null.
     */
    public MediaStreamTrackDesc getSource()
    {
        return weakSource.get();
    }

    /**
     * @return the ideal quality for this track projection.
     */
    int getIdealIndex()
    {
        return idealIndex;
    }

    /**
     * Update the ideal quality for this track projection.
     *
     * @param value the ideal quality for this track projection.
     */
    void setIdealIndex(int value)
    {
        idealIndex = value;
    }

    /**
     * Gets the target index value for this track projection.
     *
     * @return the target index value for this track projection.
     */
    int getTargetIndex()
    {
        return targetIndex;
    }

    /**
     * Sets the target index value for this track projection.
     *
     * @param value the new target index value for this track projection.
     */
    void setTargetIndex(int value)
    {
        targetIndex = value;
    }

    /**
     * Determines whether an RTP packet needs to be accepted or not.
     *
     * @param rtpPacket the RTP packet to determine whether to accept or not.
     * @return true if the packet is accepted, false otherwise.
     */
    public boolean accept(@NotNull RawPacket rtpPacket)
    {
        AdaptiveTrackProjectionContext
            contextCopy = getContext(RawPacket.getPayloadType(rtpPacket));

        // XXX We want to let the context know that the stream has been
        // suspended so that it can raise the needsKeyframe flag and also allow
        // it to compute a sequence number delta when the target becomes > -1.

        int targetIndexCopy = targetIndex;
        boolean accept = contextCopy.accept(rtpPacket, targetIndexCopy);

        if (contextCopy.needsKeyframe() && targetIndexCopy > -1)
        {
            MediaStreamTrackDesc source = getSource();
            if (source != null)
            {
                ((RTPTranslatorImpl) source
                    .getMediaStreamTrackReceiver()
                    .getStream()
                    .getRTPTranslator())
                    .getRtcpFeedbackMessageSender()
                    .requestKeyframe(targetSsrc);
            }
        }

        return accept;
    }

    /**
     * Gets or creates the adaptive track projection context that corresponds to
     * the payload type that is specified as a parameter. If the payload type
     * is different from {@link #contextPayloadType}, then a new adaptive track
     * projection context is created that is appropriate for the new payload
     * type.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread (the translator
     * thread) accessing this method at a time.
     *
     * @param payloadType the payload type of the adaptive track projection to
     * get or create.
     * @return the adaptive track projection context that corresponds to
     * the payload type that is specified as a parameter.
     */
    private synchronized
    AdaptiveTrackProjectionContext getContext(int payloadType)
    {
        if (context == null || contextPayloadType != payloadType)
        {
            MediaStreamTrackDesc source = getSource();
            MediaFormat format = source
                .getMediaStreamTrackReceiver()
                .getStream().getDynamicRTPPayloadTypes().get((byte) payloadType);

            context = makeContext(source, format);
            contextPayloadType = payloadType;
            return context;
        }
        else
        {
            return context;
        }
    }

    /**
     * Utility/factory method that creates the appropriate adaptive track
     * projection context based on the media format that is specified as a
     * parameter. Note that a media format is the same thing as a payload type
     * (there's a one-to-one mapping between payload type and media format).
     *
     * @param track the ssrc of the track projection.
     * @param format the media format.
     * @return an adaptive track projection context that corresponds to the
     * media format that is specified as a parameter.
     */
    private static AdaptiveTrackProjectionContext makeContext(
        @NotNull MediaStreamTrackDesc track, @NotNull MediaFormat format)
    {
        if (Constants.VP8.equalsIgnoreCase(format.getEncoding())
            && track.getRTPEncodings().length > 1)
        {
            long ssrc = track.getRTPEncodings()[0].getPrimarySSRC();
            return new VP8AdaptiveTrackProjectionContext(ssrc);
        }
        else
        {
            return new GenericAdaptiveTrackProjectionContext(format);
        }
    }

    /**
     * Rewrites an RTP packet and it returns any additional RTP packets that
     * need to be piggy-backed.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @return any piggy-backed packets to include with the packet.
     */
    RawPacket[] rewriteRtp(@NotNull RawPacket rtpPacket)
        throws RewriteException
    {
        AdaptiveTrackProjectionContext contextCopy = context;
        if (contextCopy == null)
        {
            return EMPTY_PACKET_ARR;
        }

        RawPacketCache incomingRawPacketCache = null;
        MediaStreamTrackDesc source = getSource();
        if (source != null)
        {
            MediaStreamImpl
                stream = source.getMediaStreamTrackReceiver().getStream();

            if (stream != null)
            {
                CachingTransformer
                    cachingTransformer = stream.getCachingTransformer();

                if (cachingTransformer != null)
                {
                    incomingRawPacketCache
                        = cachingTransformer.getIncomingRawPacketCache();
                }
                else
                {
                    logger.warn("incoming packet cache is null.");
                }
            }
            else
            {
                logger.warn("stream is null.");
            }
        }

        return contextCopy.rewriteRtp(rtpPacket, incomingRawPacketCache);
    }

    /**
     * Rewrites an RTCP packet.
     *
     * @param rtcpPacket the RTCP packet to rewrite.
     * @return true to let the RTCP packet through, false to drop.
     */
    public boolean rewriteRtcp(@NotNull RawPacket rtcpPacket)
    {
        AdaptiveTrackProjectionContext contextCopy = context;
        if (contextCopy == null)
        {
            return true;
        }

        return contextCopy.rewriteRtcp(rtcpPacket);
    }

    /**
     * @return the SSRC of the track projection.
     */
    public long getSSRC()
    {
        return targetSsrc;
    }
}