/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket.frame;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.reactivesocket.Frame;
import io.reactivesocket.FrameType;
import io.reactivesocket.util.BitUtil;

import java.nio.ByteBuffer;

/**
 * Per connection frame flyweight.
 *
 * Not the latest frame layout, but close.
 * Does not include
 * - fragmentation / reassembly
 * - encode should remove Type param and have it as part of method name (1 encode per type?)
 *
 * Not thread-safe. Assumed to be used single-threaded
 */
public class FrameHeaderFlyweight {

    private FrameHeaderFlyweight() {}

    public static final int FRAME_HEADER_LENGTH;

    private static final int FRAME_TYPE_BITS = 6;
    private static final int FRAME_TYPE_SHIFT = 16 - FRAME_TYPE_BITS;
    private static final int FRAME_FLAGS_MASK = 0b0000_0011_1111_1111;

    public static final int FRAME_LENGTH_SIZE = 3;
    public static final int FRAME_LENGTH_MASK = 0xFFFFFF;

    private static final int FRAME_LENGTH_FIELD_OFFSET;
    private static final int FRAME_TYPE_AND_FLAGS_FIELD_OFFSET;
    private static final int STREAM_ID_FIELD_OFFSET;
    private static final int PAYLOAD_OFFSET;

    public static final int FLAGS_I = 0b10_0000_0000;
    public static final int FLAGS_M = 0b01_0000_0000;

    public static final int FLAGS_F = 0b00_1000_0000;
    public static final int FLAGS_C = 0b00_0100_0000;
    public static final int FLAGS_N = 0b00_0010_0000;

    static {
        FRAME_LENGTH_FIELD_OFFSET = 0;
        STREAM_ID_FIELD_OFFSET = FRAME_LENGTH_FIELD_OFFSET + FRAME_LENGTH_SIZE;
        FRAME_TYPE_AND_FLAGS_FIELD_OFFSET = STREAM_ID_FIELD_OFFSET + BitUtil.SIZE_OF_INT;
        PAYLOAD_OFFSET = FRAME_TYPE_AND_FLAGS_FIELD_OFFSET + BitUtil.SIZE_OF_SHORT;
        FRAME_HEADER_LENGTH = PAYLOAD_OFFSET;
    }

    public static int computeFrameHeaderLength(final FrameType frameType, int metadataLength, final int dataLength) {
        return PAYLOAD_OFFSET + computeMetadataLength(frameType, metadataLength) + dataLength;
    }

    public static int encodeFrameHeader(
            final ByteBuf byteBuf,
            final int frameLength,
            final int flags,
            final FrameType frameType,
            final int streamId
    ) {
        if ((frameLength & ~FRAME_LENGTH_MASK) != 0) {
            throw new IllegalArgumentException("Frame length is larger than 24 bits");
        }

        // frame length field needs to be excluded from the length
        encodeLength(byteBuf, FRAME_LENGTH_FIELD_OFFSET, frameLength - FRAME_LENGTH_SIZE);

        byteBuf.setInt(STREAM_ID_FIELD_OFFSET, streamId);
        short typeAndFlags = (short) (frameType.getEncodedType() << FRAME_TYPE_SHIFT | (short) flags);
        byteBuf.setShort(FRAME_TYPE_AND_FLAGS_FIELD_OFFSET, typeAndFlags);

        return FRAME_HEADER_LENGTH;
    }

    public static int encodeMetadata(
            final ByteBuf byteBuf,
            final FrameType frameType,
            final int metadataOffset,
            final ByteBuf metadata
    ) {
        int length = 0;
        final int metadataLength = metadata.readableBytes();

        if (0 < metadataLength) {
            int typeAndFlags = byteBuf.getShort(FRAME_TYPE_AND_FLAGS_FIELD_OFFSET);
            typeAndFlags |= FLAGS_M;
            byteBuf.setShort(FRAME_TYPE_AND_FLAGS_FIELD_OFFSET, (short) typeAndFlags);

            if (hasMetadataLengthField(frameType)) {
                encodeLength(byteBuf, metadataOffset, metadataLength);
                length += FRAME_LENGTH_SIZE;
            }
            byteBuf.setBytes(metadataOffset + length, metadata);
            length += metadataLength;
        }

        return length;
    }

    public static int encodeData(
            final ByteBuf byteBuf,
            final int dataOffset,
            final ByteBuf data
    ) {
        int length = 0;
        final int dataLength = data.readableBytes();

        if (0 < dataLength) {
            byteBuf.setBytes(dataOffset, data);
            length += dataLength;
        }

        return length;
    }

    // only used for types simple enough that they don't have their own FrameFlyweights
    public static int encode(
            final ByteBuf byteBuf,
            final int streamId,
            int flags,
            final FrameType frameType,
            final ByteBuf metadata,
            final ByteBuf data
    ) {
        final int frameLength = computeFrameHeaderLength(frameType, metadata.readableBytes(), data.readableBytes());

        final FrameType outFrameType;
        switch (frameType) {
            case PAYLOAD:
                throw new IllegalArgumentException("Don't encode raw PAYLOAD frames, use NEXT_COMPLETE, COMPLETE or NEXT");
            case NEXT_COMPLETE:
                outFrameType = FrameType.PAYLOAD;
                flags |= FLAGS_C | FLAGS_N;
                break;
            case COMPLETE:
                outFrameType = FrameType.PAYLOAD;
                flags |= FLAGS_C;
                break;
            case NEXT:
                outFrameType = FrameType.PAYLOAD;
                flags |= FLAGS_N;
                break;
            default:
                outFrameType = frameType;
                break;
        }

        int length = encodeFrameHeader(byteBuf, frameLength, flags, outFrameType, streamId);

        length += encodeMetadata(byteBuf, frameType, length, metadata);
        length += encodeData(byteBuf, length, data);

        return length;
    }

    public static int flags(final ByteBuf byteBuf) {
        short typeAndFlags = byteBuf.getShort(FRAME_TYPE_AND_FLAGS_FIELD_OFFSET);
        return typeAndFlags & FRAME_FLAGS_MASK;
    }

    public static FrameType frameType(final ByteBuf byteBuf) {
        int typeAndFlags = byteBuf.getShort(FRAME_TYPE_AND_FLAGS_FIELD_OFFSET);
        FrameType result = FrameType.from(typeAndFlags >> FRAME_TYPE_SHIFT);

        if (FrameType.PAYLOAD == result) {
            final int flags = typeAndFlags & FRAME_FLAGS_MASK;

            boolean complete = FLAGS_C == (flags & FLAGS_C);
            boolean next = FLAGS_N == (flags & FLAGS_N);
            if (next && complete) {
                result = FrameType.NEXT_COMPLETE;
            } else if (complete) {
                result = FrameType.COMPLETE;
            } else if (next) {
                result = FrameType.NEXT;
            } else {
                throw new IllegalArgumentException("Payload must set either or both of NEXT and COMPLETE.");
            }
        }

        return result;
    }

    public static int streamId(final ByteBuf byteBuf) {
        return byteBuf.getInt(STREAM_ID_FIELD_OFFSET);
    }

    public static ByteBuf sliceFrameData(final ByteBuf byteBuf) {
        final FrameType frameType = frameType(byteBuf);
        final int frameLength = frameLength(byteBuf);
        final int dataLength = dataLength(byteBuf, frameType);
        final int dataOffset = dataOffset(byteBuf, frameType, frameLength);
        ByteBuf result = Unpooled.EMPTY_BUFFER;

        if (0 < dataLength) {
            result = byteBuf.slice(dataOffset, dataLength);
        }

        return result;
    }

    public static ByteBuf sliceFrameMetadata(final ByteBuf byteBuf) {
        final FrameType frameType = frameType(byteBuf);
        final int frameLength = frameLength(byteBuf);
        final int metadataLength = Math.max(0, metadataLength(byteBuf, frameType, frameLength));
        int metadataOffset = metadataOffset(byteBuf);
        if (hasMetadataLengthField(frameType)) {
            metadataOffset += FRAME_LENGTH_SIZE;
        }
        ByteBuf result = Unpooled.EMPTY_BUFFER;

        if (0 < metadataLength) {
            result = byteBuf.slice(metadataOffset, metadataLength);
        }

        return result;
    }

    public static int frameLength(final ByteBuf byteBuf) {
        // frame length field was excluded from the length so we will add it to represent
        // the entire block
        return decodeLength(byteBuf, FRAME_LENGTH_FIELD_OFFSET) + FRAME_LENGTH_SIZE;
    }

    private static int metadataFieldLength(ByteBuf byteBuf, FrameType frameType, int frameLength) {
        return computeMetadataLength(frameType, metadataLength(byteBuf, frameType, frameLength));
    }

    public static int metadataLength(ByteBuf byteBuf, FrameType frameType, int frameLength) {
        int metadataOffset = metadataOffset(byteBuf);
        if (!hasMetadataLengthField(frameType)) {
            return frameLength - metadataOffset;
        } else {
            return decodeMetadataLength(byteBuf, metadataOffset);
        }
    }

    static int decodeMetadataLength(final ByteBuf byteBuf, final int metadataOffset) {
        int metadataLength = 0;

        int flags = flags(byteBuf);
        if (FLAGS_M == (FLAGS_M & flags)) {
            metadataLength = decodeLength(byteBuf, metadataOffset);
        }

        return metadataLength;
    }

    private static int computeMetadataLength(FrameType frameType, final int length) {
        if (!hasMetadataLengthField(frameType)) {
            // Frames with only metadata does not need metadata length field
            return length;
        } else {
            return length == 0 ? 0 : length + FRAME_LENGTH_SIZE;
        }
    }

    public static boolean hasMetadataLengthField(FrameType frameType) {
        return frameType.canHaveData();
    }

    private static void encodeLength(
            final ByteBuf byteBuf,
            final int offset,
            final int length
    ) {
        if ((length & ~FRAME_LENGTH_MASK) != 0) {
            throw new IllegalArgumentException("Length is larger than 24 bits");
        }
        // Write each byte separately in reverse order, this mean we can write 1 << 23 without overflowing.
        byteBuf.setByte(offset, length >> 16);
        byteBuf.setByte(offset + 1, length >> 8);
        byteBuf.setByte(offset + 2, length);
    }

    private static int decodeLength(final ByteBuf byteBuf, final int offset) {
        int length = (byteBuf.getByte(offset) & 0xFF) << 16;
        length |= (byteBuf.getByte(offset + 1) & 0xFF) << 8;
        length |= byteBuf.getByte(offset + 2) & 0xFF;
        return length;
    }

    public static int dataLength(final ByteBuf byteBuf, final FrameType frameType) {
        return dataLength(byteBuf, frameType, payloadOffset(byteBuf));
    }

    static int dataLength(
            final ByteBuf byteBuf,
            final FrameType frameType,
            final int payloadOffset
    ) {
        final int frameLength = frameLength(byteBuf);
        final int metadataLength = metadataFieldLength(byteBuf, frameType, frameLength);

        return frameLength - metadataLength - payloadOffset;
    }

    public static int payloadLength(final ByteBuf byteBuf) {
        final int frameLength = frameLength(byteBuf);
        final int payloadOffset = payloadOffset(byteBuf);

        return frameLength - payloadOffset;
    }

    private static int payloadOffset(final ByteBuf byteBuf) {
        int typeAndFlags = byteBuf.getShort(FRAME_TYPE_AND_FLAGS_FIELD_OFFSET);
        FrameType frameType = FrameType.from(typeAndFlags >> FRAME_TYPE_SHIFT);
        int result = PAYLOAD_OFFSET;

        switch (frameType) {
            case SETUP:
                result = SetupFrameFlyweight.payloadOffset(byteBuf);
                break;
            case ERROR:
                result = ErrorFrameFlyweight.payloadOffset(byteBuf);
                break;
            case LEASE:
                result = LeaseFrameFlyweight.payloadOffset(byteBuf);
                break;
            case KEEPALIVE:
                result = KeepaliveFrameFlyweight.payloadOffset(byteBuf);
                break;
            case REQUEST_RESPONSE:
            case FIRE_AND_FORGET:
            case REQUEST_STREAM:
            case REQUEST_CHANNEL:
                result = RequestFrameFlyweight.payloadOffset(frameType, byteBuf);
                break;
            case REQUEST_N:
                result = RequestNFrameFlyweight.payloadOffset(byteBuf);
                break;
        }

        return result;
    }

    public static int metadataOffset(final ByteBuf byteBuf) {
        return payloadOffset(byteBuf);
    }

    public static int dataOffset(ByteBuf byteBuf, FrameType frameType, int frameLength) {
        return payloadOffset(byteBuf) + metadataFieldLength(byteBuf, frameType, frameLength);
    }
}
