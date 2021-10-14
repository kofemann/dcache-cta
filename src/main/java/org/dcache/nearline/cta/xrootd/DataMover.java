package org.dcache.nearline.cta.xrootd;

import static org.dcache.xrootd.protocol.XrootdProtocol.DATA_SERVER;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.AbstractIdleService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LoggingHandler;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentMap;
import org.dcache.pool.nearline.spi.NearlineRequest;
import org.dcache.xrootd.core.XrootdAuthenticationHandlerProvider;
import org.dcache.xrootd.core.XrootdAuthorizationHandlerProvider;
import org.dcache.xrootd.core.XrootdDecoder;
import org.dcache.xrootd.core.XrootdEncoder;
import org.dcache.xrootd.core.XrootdHandshakeHandler;
import org.dcache.xrootd.plugins.ChannelHandlerFactory;
import org.dcache.xrootd.plugins.ChannelHandlerProvider;
import org.dcache.xrootd.stream.ChunkedResponseWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataMover extends AbstractIdleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataMover.class);

    private ServerBootstrap server;
    private ChannelFuture cf;

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    private final ConcurrentMap<String, ? extends NearlineRequest> pendingRequests;

    public DataMover(InetSocketAddress sa,
          ConcurrentMap<String, ? extends NearlineRequest> pendingRequests) {

        try {
            this.pendingRequests = pendingRequests;
            bossGroup = new NioEventLoopGroup();
            workerGroup = new NioEventLoopGroup();

            server = new ServerBootstrap()
                  .group(bossGroup, workerGroup)
                  .channel(NioServerSocketChannel.class)
                  .localAddress(sa)
                  .childOption(ChannelOption.TCP_NODELAY, true)
                  .childOption(ChannelOption.SO_KEEPALIVE, true)
                  .childHandler(new XrootChallenInitializer());
        } catch (Exception e) {
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void startUp() throws Exception {
        cf = server.bind().sync();
    }

    @Override
    protected void shutDown() throws Exception {

        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    @Override
    protected String serviceName() {
        return "Xrootd CTA data mover";
    }

    public InetSocketAddress getLocalSocketAddress() {
        Preconditions.checkState(cf != null, "Service is not started");
        Preconditions.checkState(cf.isDone(), "Service is not bound yet.");
        return (InetSocketAddress) cf.channel().localAddress();
    }

    private class XrootChallenInitializer extends ChannelInitializer<Channel> {

        public final List<ChannelHandlerFactory> channelHandlerFactories;
        private final ServiceLoader<ChannelHandlerProvider> channelHandlerProviders;

        XrootChallenInitializer() throws Exception {

            var pluginLoader = this.getClass().getClassLoader();
            XrootdAuthenticationHandlerProvider.setPluginClassLoader(pluginLoader);
            XrootdAuthorizationHandlerProvider.setPluginClassLoader(pluginLoader);

            channelHandlerProviders =
                  ServiceLoader.load(ChannelHandlerProvider.class, pluginLoader);

            channelHandlerFactories = new ArrayList<>();
            for (String plugin : List.of("authn:none")) {
                channelHandlerFactories.add(createHandlerFactory(plugin));
            }
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast("handshaker", new XrootdHandshakeHandler(DATA_SERVER));
            pipeline.addLast("encoder", new XrootdEncoder());
            pipeline.addLast("decoder", new XrootdDecoder());

            if (LOGGER.isDebugEnabled()) {
                pipeline.addLast("logger", new LoggingHandler(XrootChallenInitializer.class));
            }

            for (ChannelHandlerFactory factory : channelHandlerFactories) {
                pipeline.addLast("plugin:" + factory.getName(), factory.createHandler());
            }

            pipeline.addLast("chunk-writer", new ChunkedResponseWriteHandler());
            pipeline.addLast("data-server", new DataServerHandler(pendingRequests));
        }

        public final ChannelHandlerFactory createHandlerFactory(String plugin)
              throws Exception {
            for (ChannelHandlerProvider provider : channelHandlerProviders) {
                ChannelHandlerFactory factory =
                      provider.createFactory(plugin, new Properties());
                if (factory != null) {
                    LOGGER.debug("ChannelHandler plugin {} is provided by {}", plugin,
                          provider.getClass());
                    return factory;
                } else {
                    LOGGER.debug("ChannelHandler plugin {} could not be provided by {}", plugin,
                          provider.getClass());
                }
            }
            throw new NoSuchElementException("Channel handler plugin not found: " + plugin);
        }

    }
}