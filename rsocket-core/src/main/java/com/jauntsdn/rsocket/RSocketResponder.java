/*
 * Copyright 2015-2018 the original author or authors.
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

package com.jauntsdn.rsocket;

import static com.jauntsdn.rsocket.StreamErrorMappers.*;

import com.jauntsdn.rsocket.frame.*;
import com.jauntsdn.rsocket.frame.decoder.PayloadDecoder;
import com.jauntsdn.rsocket.internal.SynchronizedIntObjectHashMap;
import com.jauntsdn.rsocket.internal.UnboundedProcessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectMap;
import java.nio.channels.ClosedChannelException;
import java.util.function.Consumer;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.*;

/** Responder side of RSocket. Receives {@link ByteBuf}s from a peer's {@link RSocketRequester} */
class RSocketResponder implements ResponderRSocket {
  private static final Logger logger = LoggerFactory.getLogger(RSocketResponder.class);
  private static final Exception CLOSED_CHANNEL_EXCEPTION = new ClosedChannelException();

  static {
    CLOSED_CHANNEL_EXCEPTION.setStackTrace(new StackTraceElement[0]);
  }

  private final DuplexConnection connection;
  private final RSocket requestHandler;
  private final ResponderRSocket responderRSocket;
  private final PayloadDecoder payloadDecoder;
  private final Consumer<Throwable> errorConsumer;
  private final StreamErrorMapper streamErrorMapper;

  private final IntObjectMap<Subscription> senders;
  private final IntObjectMap<Processor<Payload, Payload>> receivers;

  private final UnboundedProcessor<ByteBuf> sendProcessor;
  private final ByteBufAllocator allocator;

  private volatile Throwable terminationError;

  RSocketResponder(
      ByteBufAllocator allocator,
      DuplexConnection connection,
      RSocket requestHandler,
      PayloadDecoder payloadDecoder,
      Consumer<Throwable> errorConsumer,
      StreamErrorMapper streamErrorMapper) {
    this.allocator = allocator;
    this.connection = connection;

    this.requestHandler = requestHandler;
    this.responderRSocket =
        (requestHandler instanceof ResponderRSocket) ? (ResponderRSocket) requestHandler : null;

    this.payloadDecoder = payloadDecoder;
    this.errorConsumer = errorConsumer;
    this.streamErrorMapper = streamErrorMapper;
    this.senders = new SynchronizedIntObjectHashMap<>();
    this.receivers = new SynchronizedIntObjectHashMap<>();

    this.sendProcessor = new UnboundedProcessor<>();

    connection.send(sendProcessor).subscribe(null, this::handleSendProcessorError);

    connection.receive().subscribe(this::handleFrame, errorConsumer);

    this.connection.onClose().doFinally(s -> terminate()).subscribe(null, errorConsumer);
  }

  @Override
  public Mono<Void> fireAndForget(Payload payload) {
    try {
      return requestHandler.fireAndForget(payload);
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public Mono<Payload> requestResponse(Payload payload) {
    try {
      return requestHandler.requestResponse(payload);
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public Flux<Payload> requestStream(Payload payload) {
    try {
      return requestHandler.requestStream(payload);
    } catch (Throwable t) {
      return Flux.error(t);
    }
  }

  @Override
  public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
    return requestHandler.requestChannel(payloads);
  }

  @Override
  public Flux<Payload> requestChannel(Payload payload, Publisher<Payload> payloads) {
    try {
      return responderRSocket != null
          ? responderRSocket.requestChannel(payload, payloads)
          : requestChannel(payloads);
    } catch (Throwable t) {
      return Flux.error(t);
    }
  }

  @Override
  public Mono<Void> metadataPush(Payload payload) {
    try {
      return requestHandler.metadataPush(payload);
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public void dispose() {
    connection.dispose();
  }

  @Override
  public boolean isDisposed() {
    return connection.isDisposed();
  }

  @Override
  public Mono<Void> onClose() {
    return connection.onClose();
  }

  void sendFrame(ByteBuf frame) {
    sendProcessor.onNext(frame);
  }

  void terminate() {
    Throwable e = this.terminationError;
    if (e == null) {
      this.terminationError = e = CLOSED_CHANNEL_EXCEPTION;
    }
    final Throwable err = e;

    synchronized (receivers) {
      receivers
          .values()
          .forEach(
              receiver -> {
                try {
                  receiver.onError(err);
                } catch (Throwable t) {
                  errorConsumer.accept(t);
                }
              });
    }
    synchronized (senders) {
      senders
          .values()
          .forEach(
              sender -> {
                try {
                  sender.cancel();
                } catch (Throwable t) {
                  errorConsumer.accept(t);
                }
              });
    }
    senders.clear();
    receivers.clear();

    requestHandler.dispose();
    sendProcessor.dispose();
  }

  private void handleSendProcessorError(Throwable t) {
    connection.dispose();
  }

  private void handleFrame(ByteBuf frame) {
    try {
      int streamId = FrameHeaderFlyweight.streamId(frame);
      Subscriber<Payload> receiver;
      FrameType frameType = FrameHeaderFlyweight.strictFrameType(frame);
      switch (frameType) {
        case REQUEST_FNF:
          handleFireAndForget(streamId, fireAndForget(payloadDecoder.apply(frame, frameType)));
          break;
        case REQUEST_RESPONSE:
          handleRequestResponse(streamId, requestResponse(payloadDecoder.apply(frame, frameType)));
          break;
        case REQUEST_N:
          handleRequestN(streamId, frame);
          break;
        case REQUEST_STREAM:
          int streamInitialRequestN = RequestStreamFrameFlyweight.initialRequestN(frame);
          Payload streamPayload = payloadDecoder.apply(frame, frameType);
          handleStream(streamId, requestStream(streamPayload), streamInitialRequestN);
          break;
        case REQUEST_CHANNEL:
          int channelInitialRequestN = RequestChannelFrameFlyweight.initialRequestN(frame);
          Payload channelPayload = payloadDecoder.apply(frame, frameType);
          handleChannel(streamId, channelPayload, channelInitialRequestN);
          break;
        case METADATA_PUSH:
          handleMetadataPush(metadataPush(payloadDecoder.apply(frame, frameType)));
          break;
        case NEXT:
          receiver = receivers.get(streamId);
          if (receiver != null) {
            receiver.onNext(payloadDecoder.apply(frame, frameType));
          }
          break;
        case NEXT_COMPLETE:
          receiver = receivers.get(streamId);
          if (receiver != null) {
            receiver.onNext(payloadDecoder.apply(frame, frameType));
            receiver.onComplete();
          }
          break;
        case COMPLETE:
          receiver = receivers.get(streamId);
          if (receiver != null) {
            receiver.onComplete();
          }
          break;
        case CANCEL:
          Subscription sender = senders.remove(streamId);
          if (sender != null) {
            sender.cancel();
          }
          break;
        case ERROR:
          receiver = receivers.get(streamId);
          if (receiver != null) {
            receiver.onError(streamErrorMapper.streamFrameToError(frame, StreamType.REQUEST));
          }
          break;
        case SETUP:
          disposeConnection(new IllegalStateException("SETUP frame received post setup"));
          break;
        case PAYLOAD:
          disposeConnection(
              new IllegalStateException(
                  "Unexpected PAYLOAD frame received: expect NEXT, NEXT_COMPLETE, COMPLETE"));
          break;
        case LEASE:
          disposeConnection(new IllegalStateException("Unexpected LEASE frame received"));
          break;
        default:
          if (logger.isDebugEnabled()) {
            logger.debug("Unexpected frame received: {}", frameType);
            logger.debug(ByteBufUtil.hexDump(frame));
          }
          break;
      }
      ReferenceCountUtil.safeRelease(frame);
    } catch (Throwable t) {
      ReferenceCountUtil.safeRelease(frame);
      throw Exceptions.propagate(t);
    }
  }

  private void handleFireAndForget(int streamId, Mono<Void> result) {
    result.subscribe(
        new BaseSubscriber<Void>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            senders.put(streamId, subscription);
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          protected void hookOnError(Throwable throwable) {
            errorConsumer.accept(throwable);
          }

          @Override
          protected void hookFinally(SignalType type) {
            removeSender(streamId);
          }
        });
  }

  private void handleRequestResponse(int streamId, Mono<Payload> response) {
    response.subscribe(
        new BaseSubscriber<Payload>() {
          private boolean isEmpty = true;

          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            senders.put(streamId, subscription);
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          protected void hookOnNext(Payload payload) {
            if (isEmpty) {
              isEmpty = false;
            }

            ByteBuf byteBuf;
            try {
              byteBuf = PayloadFrameFlyweight.encodeNextComplete(allocator, streamId, payload);
            } catch (Throwable t) {
              payload.release();
              throw Exceptions.propagate(t);
            }

            payload.release();

            sendProcessor.onNext(byteBuf);
          }

          @Override
          protected void hookOnError(Throwable throwable) {
            sendProcessor.onNext(
                streamErrorMapper.streamErrorToFrame(streamId, StreamType.RESPONSE, throwable));
          }

          @Override
          protected void hookOnComplete() {
            if (isEmpty) {
              sendProcessor.onNext(PayloadFrameFlyweight.encodeComplete(allocator, streamId));
            }
          }

          @Override
          protected void hookFinally(SignalType type) {
            removeSender(streamId);
          }
        });
  }

  private void handleStream(int streamId, Flux<Payload> response, int initialRequestN) {
    response.subscribe(
        new BaseSubscriber<Payload>() {

          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            senders.put(streamId, subscription);
            subscription.request(
                initialRequestN == Integer.MAX_VALUE ? Long.MAX_VALUE : initialRequestN);
          }

          @Override
          protected void hookOnNext(Payload payload) {
            ByteBuf byteBuf;
            try {
              byteBuf = PayloadFrameFlyweight.encodeNext(allocator, streamId, payload);
            } catch (Throwable t) {
              payload.release();
              throw Exceptions.propagate(t);
            }

            payload.release();

            sendProcessor.onNext(byteBuf);
          }

          @Override
          protected void hookOnComplete() {
            sendProcessor.onNext(PayloadFrameFlyweight.encodeComplete(allocator, streamId));
          }

          @Override
          protected void hookOnError(Throwable throwable) {
            sendProcessor.onNext(
                streamErrorMapper.streamErrorToFrame(streamId, StreamType.RESPONSE, throwable));
          }

          @Override
          protected void hookFinally(SignalType type) {
            removeSender(streamId);
          }
        });
  }

  private void handleChannel(int streamId, Payload payload, int initialRequestN) {
    UnicastProcessor<Payload> frames = UnicastProcessor.create();
    receivers.put(streamId, frames);

    Flux<Payload> payloads =
        frames
            .doOnCancel(
                () -> sendProcessor.onNext(CancelFrameFlyweight.encode(allocator, streamId)))
            .doOnRequest(
                l -> sendProcessor.onNext(RequestNFrameFlyweight.encode(allocator, streamId, l)))
            .doFinally(signalType -> removeReceiver(streamId));

    // not chained, as the payload should be enqueued in the Unicast processor before this method
    // returns
    // and any later payload can be processed
    frames.onNext(payload);

    handleStream(streamId, requestChannel(payload, payloads), initialRequestN);
  }

  private void handleMetadataPush(Mono<Void> result) {
    result.subscribe(
        new BaseSubscriber<Void>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          protected void hookOnError(Throwable throwable) {
            errorConsumer.accept(throwable);
          }
        });
  }

  private void disposeConnection(Throwable t) {
    terminationError = t;

    connection
        .sendOne(ErrorFrameFlyweight.encode(allocator, 0, t))
        .doFinally(s -> connection.dispose())
        .subscribe(null, errorConsumer);
    errorConsumer.accept(t);
  }

  private void handleRequestN(int streamId, ByteBuf frame) {
    Subscription sender = senders.get(streamId);
    if (sender != null) {
      int n = RequestNFrameFlyweight.requestN(frame);
      sender.request(n == Integer.MAX_VALUE ? Long.MAX_VALUE : n);
    }
  }

  private void removeReceiver(int streamId) {
    /*on termination receivers are explicitly cleared to avoid removing from map while iterating over one
    of its views*/
    if (terminationError == null) {
      receivers.remove(streamId);
    }
  }

  private void removeSender(int streamId) {
    /*on termination receivers are explicitly cleared to avoid removing from map while iterating over one
    of its views*/
    if (terminationError == null) {
      senders.remove(streamId);
    }
  }

  void gracefulDispose(String msg) {}
}
