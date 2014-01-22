package stork.module.ftp;

import java.net.*;
import java.util.*;
import java.nio.charset.*;

import stork.util.*;

import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.buffer.*;
import io.netty.channel.nio.*;
import io.netty.channel.socket.*;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.*;
import io.netty.handler.codec.string.*;
import io.netty.handler.codec.base64.*;
import io.netty.util.*;

import org.ietf.jgss.*;
import org.gridforum.jgss.*;

// An abstraction of an FTP control channel. This class takes care of command
// pipelining, extracting replies, and maintaining channel state. Right now,
// the channel is mostly concurrency-safe, though issues could arise if
// arbitrary commands entered the pipeline during a command sequence.
// Therefore, it is ill-advised to have more than one subsystem issuing
// commands at a time.

public class FTPChannel {
  private Type type;
  private ChannelFuture future;
  private SecurityContext security;
  private Deque<Command> handlers;

  private Reply welcome;
  private FeatureSet features = new FeatureSet();

  // Any FTP server that adheres to specifications will use UTF-8, but let's
  // not preclude the possibility of being able to configure the encoding.
  private Charset encoding = CharsetUtil.UTF_8;

  // FIXME: We should use something system-wide.
  private static EventLoopGroup group = new NioEventLoopGroup();

  // Internal representation of the remote server type.
  private static enum Type {
    ftp(21), gridftp(2811);

    int port;

    Type(int def_port) {
      port = def_port;
    }
  }

  public FTPChannel(String uri) {
    this(URI.create(uri));
  } public FTPChannel(URI uri) {
    this(uri.getScheme(), uri.getHost(), uri.getPort());
  } public FTPChannel(String host, int port) {
    this(null, host, port);
  } public FTPChannel(InetAddress addr, int port) {
    this(null, addr, port);
  } public FTPChannel(String proto, String host, int port) {
    this(proto, host, null, port);
  } public FTPChannel(String proto, InetAddress addr, int port) {
    this(proto, null, addr, port);
  }

  // All the constructors delegate to this.
  private FTPChannel(String proto, String host, InetAddress addr, int port) {
    type = (proto == null) ? Type.ftp : Type.valueOf(proto.toLowerCase());
    if (port <= 0) port = type.port;

    Bootstrap b = new Bootstrap();
    b.group(group).channel(NioSocketChannel.class).handler(new Initializer());
    future = (host != null) ? b.connect(host, port) : b.connect(addr, port);

    handlers = new ArrayDeque<Command>();
  }

  // Handles attaching the necessary codecs.
  class Initializer extends ChannelInitializer<SocketChannel> {
    public void initChannel(SocketChannel ch) throws Exception {
      ChannelPipeline p = ch.pipeline();

      p.addLast("reply_decoder", new ReplyDecoder(20480));
      p.addLast("reply_handler", new ReplyHandler());

      p.addLast("command_encoder", new CommandEncoder());

    }
  }

  // A reply from the server.
  public class Reply {
    public final int code;
    private final ByteBuf[] lines;

    private Reply(int code, ByteBuf[] lines) {
      if (code < 100 || code >= 700)
        throw new RuntimeException("Bad reply code: "+code);
      this.code = code;
      this.lines = lines;
    }

    // Get the number of lines.
    public int length() {
      return lines.length;
    }

    // Get a line by number.
    public String line(int i) {
      return lines[i].toString(encoding);
    }

    // Get an array of all the lines as strings.
    public String[] lines() {
      String[] arr = new String[lines.length];
      for (int i = 0; i < lines.length; i++)
        arr[i] = line(i);
      return arr;
    }

    // Return a description meaningful to a human based on a reply code.
    public String description() {
      return FTPMessages.fromCode(code);
    }

    // Return an exception based on the reply.
    public RuntimeException asError() {
      return new RuntimeException(description());
    }

    // Get the message as a string. If the reply is one line, the message is
    // the first line. If it's multi-lined, the message is the part between the
    // first and last lines.
    public String message() {
      if (lines.length <= 1)
        return line(0);
      StringBuilder sb = new StringBuilder();
      for (int i = 1, z = lines.length-1; i < z; i++) {
        sb.append(line(i));
        if (i != z) sb.append('\n');
      }
      return sb.toString();
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0, z = lines.length-1; i <= z; i++) if (i != z)
        sb.append(code).append('-').append(line(i)).append('\n');
      else
        sb.append(code).append(' ').append(line(i));
      return sb.toString();
    }

    // Returns true if this reply is intermediate and further replies are
    // expected before the command has been fulfilled.
    public boolean isPreliminary() {
      return code/100 == 1;
    }

    // Returns true if the reply indicates the command completed successfully.
    public boolean isComplete() {
      return code/100 == 2;
    }

    // Returns true if the reply indicates the command cannot be carried out
    // unless followed up with another command.
    public boolean isIncomplete() {
      return code/100 == 3;
    }

    // Returns true if the reply is negative (i.e., it indicates a failure).
    public boolean isNegative() {
      return code/100 == 4 || code/100 == 5;
    }

    // Returns true if the message is protected, i.e. its message is a payload
    // containing a specially encoded reply. In most cases, users will not see
    // replies of this type directly as the channel handlers will transparently
    // decode them. However, this can happen if server behaves improperly by
    // sending replies of this type with no security context set.
    public boolean isProtected() {
      return code/100 == 6;
    }
  }

  // Handles decoding replies from the server. This uses functionality from
  // LineBasedFrameDecoder to split incoming bytes on a per-line basis, then
  // parses each line as an FTP reply line, and buffers lines until a reply is
  // fully formed. It supports decoding (arbitrarily-nested) protected replies
  // when a security context is present.
  class ReplyDecoder extends LineBasedFrameDecoder {
    List<ByteBuf> lines = new LinkedList<ByteBuf>();
    String codestr;
    int code;

    public ReplyDecoder(int len) {
      super(len);
    }

    // Convenience method for parsing reply text in a self-contained byte
    // buffer, used by decodeProtectReply and anywhere else this might be
    // needed. This works by simulating incoming data in a channel pipeline,
    // though no channel context is present. This may be an issue if Netty ever
    // changes to use the channel context in line splitting, in which case we
    // will need to come up with another solution.
    private Reply decodeProtectedReply(Reply reply) throws Exception {
      // Do nothing if the security context has not been initialized. Really
      // this is an error on the part of the server, but there's a chance the
      // client code will know what to do.
      if (security == null)
        return reply;

      ReplyDecoder rd = new ReplyDecoder(20480);
      for (ByteBuf eb : reply.lines) {
        ByteBuf db = security.unprotect(Base64.decode(eb));
        Reply r = rd.decode(null, db);
        if (r != null)
          return r;
      } throw new RuntimeException("Bad reply from server.");
    }

    // Override the decode from LineBasedFrameDecoder to feed incoming lines to
    // decodeReplyLine.
    protected Reply decode(ChannelHandlerContext ctx, ByteBuf buffer)
    throws Exception {
      buffer = (ByteBuf) super.decode(ctx, buffer);
      if (buffer == null)
        return null;  // We haven't gotten a full line.
      Reply r = decodeReplyLine(buffer);
      if (r == null)
        return null;  // We haven't gotten a full reply.
      if (r.isProtected())
        r = decodeProtectedReply(r);
      return r;
    }

    // Decode a reply from a string. This should only be called if the input
    // buffer is a properly framed line with the end-of-line character(s)
    // stripped.
    protected Reply decodeReplyLine(ByteBuf msg) {
      try {
        return innerDecodeReplyLine(msg);
      } catch (Exception e) {
        // TODO: Replace this with a real exception.
        throw new RuntimeException("Bad reply from server.");
      }
    } protected Reply innerDecodeReplyLine(ByteBuf msg) throws Exception {
      // Some implementation supposedly inserts null bytes, ugh.
      if (msg.getByte(0) == 0)
        msg.readByte();

      char sep = '-';

      // Extract the reply code and separator.
      if (lines.isEmpty()) {
        codestr = msg.toString(0, 3, encoding);
        code = Integer.parseInt(codestr);
        msg.skipBytes(3);
        sep = (char) msg.readByte();
      } else if (msg.readableBytes() >= 4) {
        String s = msg.toString(0, 4, encoding);
        sep = s.charAt(3);
        if (s.startsWith(codestr) && (sep == '-' || sep == ' '))
          msg.skipBytes(4);
        else
          sep = '-';
      }

      // Save the rest of the message.
      lines.add(msg);

      // Act based on the separator.
      switch (sep) {
        case ' ':
          ByteBuf[] la = lines.toArray(new ByteBuf[lines.size()]);
          lines = new LinkedList<ByteBuf>();
          return new Reply(code, la);
        case '-': return null;
        default : throw  null;
      }
    }
  }

  // Handles replies as they are received.
  class ReplyHandler extends SimpleChannelInboundHandler<Reply> {
    public void messageReceived(ChannelHandlerContext ctx, Reply reply)
    throws Exception {
      System.out.println(reply);
      if (welcome == null && reply.code == 220)
        welcome = reply;
      else
        feedHandler(reply);
    }

    // TODO: How should we handle exceptions? Which exceptions can this thing
    // receive anyway?
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
      t.printStackTrace();
    }
  }

  // A security context represents all of the state of the channel with respect
  // to security and provides methods for encoding and decoding messages.
  interface SecurityContext {
    // Returns true if the session has been established.
    boolean established();

    // Given an input token, return an output token which should be given to
    // the remote server via ADAT, or null if the session is established.
    ByteBuf handshake(ByteBuf in) throws Exception;

    // Given a byte buffer containing protected bytes (not a Base64 encoding
    // thereof), decode the bytes back into the plaintext payload.
    ByteBuf unprotect(ByteBuf buf) throws Exception;

    // Given a byte buffer containing some information, generate a command with
    // a payload protected according to the current security context.
    ByteBuf protect(ByteBuf buf) throws Exception;
  }

  // A security context based on GSSAPI.
  class GSSSecurityContext implements SecurityContext {
    GSSContext context;

    public GSSSecurityContext(GSSCredential cred) throws GSSException {
      GSSManager manager = ExtendedGSSManager.getInstance();
      Oid oid = new Oid("1.3.6.1.4.1.3536.1.1");
      String host = "trestles-dm.sdsc.xsede.org";
      GSSName peer = manager.createName(
        "host@"+host, GSSName.NT_HOSTBASED_SERVICE);
      context = manager.createContext(
        peer, oid, cred, cred.getRemainingLifetime());
      context.requestCredDeleg(true);
      context.requestConf(true);
    }

    // Utility method for extracting a buffer as a byte array.
    private byte[] bytes(ByteBuf buf) {
      byte[] b;
      if (buf.hasArray())
        b = buf.array();
      else
        buf.getBytes(buf.readerIndex(), b = new byte[buf.readableBytes()]);
      return b;
    }

    public boolean established() {
      return context.isEstablished();
    }

    public ByteBuf handshake(ByteBuf in) throws GSSException {
      if (established())
        return null;
      byte[] i = bytes(in);
      byte[] o = context.initSecContext(i, 0, i.length);
      return Unpooled.wrappedBuffer(o);
    }

    public ByteBuf unprotect(ByteBuf buf) throws GSSException {
      byte[] b = bytes(buf);
      return Unpooled.wrappedBuffer(context.unwrap(b, 0, b.length, null));
    }

    public ByteBuf protect(ByteBuf buf) throws GSSException {
      byte[] b = bytes(buf);
      return Unpooled.wrappedBuffer(context.wrap(b, 0, b.length, null));
    }
  }

  // Handles encoding commands using the current charset and security
  // mechanism. We can assume every message is a single command with no
  // newlines.
  class CommandEncoder extends MessageToMessageEncoder<Object> {
    protected void encode
      (ChannelHandlerContext ctx, Object msg, List<Object> out)
    throws Exception {
      System.out.println(msg);
      ByteBuf b = Unpooled.wrappedBuffer(msg.toString().getBytes(encoding));

      if (security != null) {
        b = Base64.encode(security.protect(b), false);
        out.add(Unpooled.wrappedBuffer("ENC ".getBytes(encoding)));
      } 

      out.add(b);
      out.add(Unpooled.wrappedBuffer("\r\n".getBytes(encoding)));
    }
  }

  // Used internally to extract the channel from the future.
  private Channel channel() {
    return future.syncUninterruptibly().channel();
  }

  // Try to authenticate using a username and password.
  public Bell<Reply> authorize() {
    return authorize("anonymous", "");
  } public Bell<Reply> authorize(String user) {
    return authorize(user, "");
  } public Bell<Reply> authorize(final String user, final String pass) {
    final Bell<Reply> bell = new Bell<Reply>();
    new Command("USER", user) {
      public void handle(Reply r) {
        if (r.code == 331)
          new Command("PASS", pass).promise(bell);
        else if (r.isComplete())
          promise(bell);
        else
          promise(bell);
      }
    };

    return bell;
  }

  // Try to authenticate with a GSS credential.
  public Bell<Reply> authenticate(GSSCredential cred) throws Exception {
    final GSSSecurityContext sec = new GSSSecurityContext(cred);
    final Bell<Reply> bell = new Bell<Reply>() {
      protected void done(Reply r) {
        if (r.isComplete())
          security = sec;
      }
    };

    new Command("AUTH GSSAPI") {
      public void handle(Reply r) {
        switch (r.code/100) {
          case 3:  handshake(sec, Unpooled.EMPTY_BUFFER).promise(bell);
          case 1:  return;
          case 2:  promise(bell); return;
          default: throw r.asError();
        }
      }
    };

    return bell;
  }

  // Handshake procedure for all authentication types. The input byte buffer
  // should be the raw binary token, not a Base64 encoding.
  private Bell<Reply> handshake(final SecurityContext sec, ByteBuf it) {
    final Bell<Reply> bell = new Bell<Reply>();

    try {
      ByteBuf ot = Base64.encode(sec.handshake(it), false);

      new Command("ADAT", ot.toString(encoding)) {
        public void handle(Reply r) throws Exception {
          switch (r.code/100) {
            case 3:
              ByteBuf token = Base64.decode(r.lines[0].skipBytes(5));
              handshake(sec, Unpooled.EMPTY_BUFFER).promise(bell);
            case 1:
              return;
            case 2:
              promise(bell); return;
            default:
              throw r.asError();
          }
        }
      };
    } catch (Exception e) {
      bell.ring(e);
    }

    return bell;
  }

  // A structure used to hold a set of features supported by a server.
  // TODO: This should propagate control channel errors.
  class FeatureSet {
    private boolean inProgress = false;
    private Set<String> features;
    private Set<CheckBell> checks;

    // Custom bell which is associated with a given command query.
    abstract class CheckBell extends Bell<Boolean> {
      abstract String cmd();
    }

    // Returns true if feature detection has been performed and all future
    // checks will be resolved immediately.
    public synchronized boolean isDone() {
      return features != null && !inProgress;
    }

    // Asynchronously check if a command is supported.
    public synchronized Bell<Boolean> supports(final String cmd) {
      CheckBell bell = new CheckBell() {
        String cmd() { return cmd; }
      };

      if (features == null) {
        // This is the first time we've been called, initialize state...
        inProgress = true;
        features = new HashSet<String>();
        checks = new HashSet<CheckBell>();

        // ...and pipe all the check commands.
        checks.add(bell);
        new Command("HELP").promise(parseHelpReply());
        new Command("HELP SITE").promise(parseSiteReply());
        new Command("FEAT").promise(parseFeatReply());
        new Command(null) {
          public void handle() { finalizeChecks(); }
        };
      } else if (inProgress) {
        // Still waiting; queue the bell.
        checks.add(bell);
      } else {
        bell.ring(isSupported(cmd));
      }

      return bell;
    }

    // Synchronously check for command support. Used internally.
    private synchronized boolean isSupported(String cmd) {
      return features != null && features.contains(cmd);
    }

    // Add a feature to the feature set.
    private synchronized void addFeature(String cmd) {
      features.add(cmd);
    }

    // Parse a HELP reply and update features.
    private Bell<Reply> parseHelpReply() {
      return new Bell<Reply>() {
        public void done(Reply r) {
          if (!r.isComplete()) return;
          for (int i = 1; i < r.length()-1; i++)
          for (String c : r.line(i).trim().toUpperCase().split(" "))
            if (!c.contains("*")) addFeature(c);
          updateChecks();
        }
      };
    }

    // Parse a FEAT reply and update features.
    private Bell<Reply> parseFeatReply() {
      return new Bell<Reply>() {
        public void done(Reply r) {
          if (!r.isComplete()) return;
          for (int i = 1; i < r.length()-1; i++)
            addFeature(r.line(i).trim().split(" ", 2)[0].toUpperCase());
          updateChecks();
        }
      };
    }

    // Parse a HELP SITE reply and update features.
    private Bell<Reply> parseSiteReply() {
      return new Bell<Reply>() {
        public void done(Reply r) {
          // TODO
        }
      };
    }

    // Iterate over the queued checks, and resolve them if a command is known
    // to be supported.
    private synchronized void updateChecks() {
      for (Iterator<CheckBell> it = checks.iterator(); it.hasNext();) {
        CheckBell b = it.next();
        if (isSupported(b.cmd())) {
          b.ring(true);
          it.remove();
        }
      }
    }

    // Ring this when no more commands will be issued to check support. It will
    // resolve all unsupported commands with false and update the state to
    // reflect the command list has been finalized.
    private synchronized void finalizeChecks() {
      updateChecks();
      for (CheckBell b : checks)
        b.ring(false);
      inProgress = false;
      checks = null;
    }
  }

  // Checks if a command is supported by the server.
  public Bell<Boolean> supports(String cmd) {
    return features.supports(cmd);
  } private List<Bell<Boolean>> supportsMulti(String... cmd) {
    List<Bell<Boolean>> bs = new ArrayList<Bell<Boolean>>(cmd.length);
    for (String c : cmd)
      bs.add(supports(c));
    return bs;
  } public Bell<Boolean> supportsAny(String... cmd) {
    return Bell.or(supportsMulti(cmd));
  } public Bell<Boolean> supportsAll(String... cmd) {
    return Bell.and(supportsMulti(cmd));
  }

  // Append the given command to the handler queue. If the command is a sync
  // command and there is nothing else in the queue, just fulfill it
  // immediately and don't put it in the queue. This will return whether the
  // command handler was appended or not (currently, it is always true).
  private boolean appendHandler(Command c) {
    synchronized (handlers) {
      if (!c.isSync() || !handlers.isEmpty())
        return handlers.add(c);
    }

    // If we didn't return, it's a sync. Handling a sync is fast, but we should
    // release handlers as soon as possible.
    c.internalHandle(null);
    return true;
  }

  // "Feed" the topmost handler a reply. If the reply is a preliminary reply,
  // it will pop the command handler and any sync commands between it and the
  // next non-sync command (or the end of the queue). In order to release the
  // monitor on the queue as soon as possible, the command handlers are not
  // called until after the queue has been modified.
  private void feedHandler(Reply reply) {
    Command handler;
    List<Command> syncs = null;

    // We should try to release this as soon as possibile, so don't do anything
    // that might take a long time in here.
    synchronized (handlers) {
      assert !handlers.isEmpty();

      if (reply.isPreliminary()) {
        handler = handlers.peek();
      } else {
        handler = handlers.pop();

        // Remove all the syncs.
        Command peek = handlers.peek();
        if (peek != null && peek.isSync()) {
          syncs = new LinkedList<Command>();
          do {
            syncs.add(peek);
            handlers.pop();
            peek = handlers.peek();
          } while (peek != null && peek.isSync());
        }
      }
    }

    // Now we can call the handlers.
    handler.internalHandle(reply);
    if (syncs != null) for (Command sync : syncs)
      sync.internalHandle(null);
  }

  // Used internally to write commands to the channel.
  private synchronized void writeCmd(final Object cmd, final Object... more) {
    // Write a small object which will flatten the passed objects when it's
    // ready to be written.
    channel().writeAndFlush(new Object() {
      public String toString() {
        StringBuilder sb = new StringBuilder(cmd.toString());
        for (Object o : more)
          sb.append(" ").append(o);
        return sb.toString();
      }
    });
  }

  public synchronized void printCommandStack() {
    int i = 0;
    System.out.println("<COMMAND STACK DUMP>");
    for (Command c : handlers) {
      i++;
      System.out.println("    "+i+": "+c.cmd);
    }
    System.out.println("</COMMAND STACK DUMP>");
  }

  // This is sort of the workhorse of the channel. Instantiate one of these to
  // send a command across this channel. The newly instantiated object will
  // serve as a "future" for the server's ultimate reply. For example, a simple
  // authorization routine could be written like:
  //
  //   FTPChannel ch = new FTPChannel(...);
  //   Reply r = ch.new Command("USER bob") {
  //     public void handle(Reply r) {
  //       if (r.code == 331)
  //         new Command("PASS monkey");
  //     }
  //   }.sync();
  //
  // This class's constructor will cause the command to be written to the
  // channel and placed in the handler queue. This class can be anonymously
  // subclassed to write command handlers inline, or can be subclassed normally
  // for repeat issue of the command.
  public class Command {
    private String cmd;
    private Bell<Reply> bell = new Bell<Reply>();
    private final boolean isSync;

    // Constructing this will automatically cause the given command to be
    // written to the server. Typically, the passed cmd will be a string, but
    // can be anything. The passed arguments will be stringified and
    // concatenated with spaces for convenience. If cmd is null, nothing is
    // actually written to the server and the command serves as a sort of
    // "sync" for client code. Calling sync() on it will block until it has
    // been reached in the pipeline.
    public Command(Object cmd, Object... more) {
      this.cmd = cmd + StorkUtil.join(more);
      synchronized (FTPChannel.this) {
        isSync = (cmd == null);
        appendHandler(this);
        if (cmd != null)
          writeCmd(cmd, (Object[]) more);
      }
    }

    // See the comment at the top of the class definition for why these are
    // implemented the way they are.
    public synchronized final boolean isSync() {
      return isSync;
    } public synchronized final boolean isDone() {
      return bell.isDone();
    }

    // Promise to ring the given bell when this command is fulfilled.
    public synchronized Bell<Reply> promise(Bell<Reply> bell) {
      this.bell.promise(bell);
      return bell;
    }

    // This is called internally by the channel handler whenever a reply comes
    // in. It handles passing the received reply to handle(). Once this is
    // passed a non-preliminary reply, it sets the reply future
    private void internalHandle(Reply r) {
      try {
        synchronized (this) { handle(r); }
      } catch (Exception e) {
        bell.ring(e);
      } finally {
        if (isSync())
          bell.ring();
        else if (!r.isPreliminary())
          bell.ring(r);
      }
    }

    // Users should subclass this and override handle() to deal with commands
    // as they arrive. This method may be called multiple times if preliminary
    // replies are received. A non-preliminary reply indicates that the handler
    // will not be called again. If an exception is thrown here, it will be
    // silently ignored. Note that the code that calls this is synchronized so that
    // it will be called in the order replies are received and 
    public void handle() throws Exception {
      // The default implementation does nothing.
    } public void handle(Reply r) throws Exception {
      handle();
    }

    // This is used to wait until the future has been resolved. The ultimate
    // reply will be returned, unless this was a sync command, in which case
    // null will be returned.
    public synchronized Reply sync() {
      return bell.sync();
    }
  }

  public static void main(String[] args) throws Exception {
    FTPChannel ch = new FTPChannel("ftp://didclab-ws8/");
    ch.authorize().sync();
    //GSSCredential cred =
      //StorkGSSCred.fromFile("/tmp/x509up_u1000").credential();
    //ch.authenticate(cred);
    ch.new Command("MLSC /").sync();
  }
}