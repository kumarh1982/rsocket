package io.rsocket.keepalive;

import io.netty.buffer.ByteBuf;
import io.rsocket.Closeable;
import io.rsocket.resume.ResumableDuplexConnection;
import java.util.function.Consumer;

public interface KeepAliveHandler {

  Consumer<ByteBuf> start(KeepAlive keepAlive, Runnable onTimeout);

  class DefaultKeepAliveHandler implements KeepAliveHandler {
    private final Closeable duplexConnection;

    public DefaultKeepAliveHandler(Closeable duplexConnection) {
      this.duplexConnection = duplexConnection;
    }

    @Override
    public Consumer<ByteBuf> start(KeepAlive keepAlive, Runnable onTimeout) {
      duplexConnection.onClose().doFinally(s -> keepAlive.stop()).subscribe();
      return keepAlive.onTimeout(onTimeout).start();
    }
  }

  class ResumableKeepAliveHandler implements KeepAliveHandler {
    private final ResumableDuplexConnection resumableDuplexConnection;

    public ResumableKeepAliveHandler(ResumableDuplexConnection resumableDuplexConnection) {
      this.resumableDuplexConnection = resumableDuplexConnection;
    }

    @Override
    public Consumer<ByteBuf> start(KeepAlive keepAlive, Runnable onTimeout) {
      resumableDuplexConnection.onResume(keepAlive::start);
      resumableDuplexConnection.onDisconnect(keepAlive::stop);
      return keepAlive
          .resumeState(resumableDuplexConnection)
          .onTimeout(resumableDuplexConnection::disconnect)
          .start();
    }
  }
}
