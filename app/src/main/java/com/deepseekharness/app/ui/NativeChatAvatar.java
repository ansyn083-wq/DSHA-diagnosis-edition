package com.deepseekharness.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageButton;
import android.widget.ImageView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Shows the authenticated account's public profile image without forwarding account credentials. */
final class NativeChatAvatar {
  private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024;
  private static final OkHttpClient IMAGES =
      new OkHttpClient.Builder()
          .proxy(Proxy.NO_PROXY)
          .dns(NativeChatAvatar::publicAddresses)
          .followRedirects(false)
          .callTimeout(6, TimeUnit.SECONDS)
          .build();
  private final ChatHomeActivity host;
  private final ImageButton button;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Future<?> pending;
  private long generation;
  private String cachedUrl = "";
  private Bitmap cached;

  NativeChatAvatar(ChatHomeActivity host) {
    this.host = host;
    button = host.findViewById(R.id.native_chat_avatar);
    button.setContentDescription(UiText.choose("头像，账号与 API", "Profile picture, account and API"));
    button.setOnClickListener(view -> host.showChatAccount());
  }

  void refresh(NativeChatRpc rpc) {
    cancel();
    long request = generation;
    String previousUrl = cachedUrl;
    Bitmap previous = cached;
    String locale = new ConfigStore(host).getUiLanguage();
    pending =
        worker.submit(
            () -> {
              String url = "";
              Bitmap image = null;
              try {
                url = rpc.accountAvatarUrl(Constants.DSH_VERSION, locale);
                image = url.equals(previousUrl) && previous != null ? previous : download(url);
              } catch (IOException | RuntimeException unavailable) {
                // Profile and image availability must not gate chat startup or account management.
              }
              String loadedUrl = url;
              Bitmap loaded = image;
              host.runOnUiThread(
                  () -> {
                    if (request != generation || host.isFinishing()) return;
                    cachedUrl = loadedUrl;
                    cached = loaded;
                    if (loaded == null) {
                      showPlaceholder();
                    } else {
                      button.setScaleType(ImageView.ScaleType.CENTER_CROP);
                      button.setImageDrawable(new BitmapDrawable(host.getResources(), loaded));
                    }
                  });
            });
  }

  void clear() {
    cancel();
    showPlaceholder();
  }

  void close() {
    clear();
    cached = null;
    worker.shutdownNow();
  }

  private void cancel() {
    generation++;
    if (pending != null) {
      pending.cancel(true);
      pending = null;
    }
  }

  private void showPlaceholder() {
    button.setScaleType(ImageView.ScaleType.CENTER);
    button.setImageResource(R.drawable.ic_native_chat_account);
  }

  private static List<InetAddress> publicAddresses(String hostname) throws UnknownHostException {
    List<InetAddress> addresses = new ArrayList<>();
    for (InetAddress address : Dns.SYSTEM.lookup(hostname)) {
      if (!address.isAnyLocalAddress()
          && !address.isLoopbackAddress()
          && !address.isLinkLocalAddress()
          && !address.isSiteLocalAddress()
          && !address.isMulticastAddress()
          && !isPrivateRange(address.getAddress())) {
        addresses.add(address);
      }
    }
    if (addresses.isEmpty()) throw new UnknownHostException("No public address for profile image");
    return addresses;
  }

  private static boolean isPrivateRange(byte[] address) {
    if (address.length == 16) return (address[0] & 0xFE) == 0xFC;
    int first = address[0] & 0xFF;
    int second = address[1] & 0xFF;
    return first == 0 || first == 100 && second >= 64 && second <= 127;
  }

  private static Bitmap download(String url) throws IOException {
    HttpUrl target = HttpUrl.parse(url);
    if (target == null
        || !target.isHttps()
        || target.port() != 443
        || !target.username().isEmpty()
        || !target.password().isEmpty()) return null;
    Request request = new Request.Builder().url(target).build();
    try (var response = IMAGES.newCall(request).execute()) {
      var body = response.body();
      if (!response.isSuccessful() || body == null || body.contentLength() > MAX_IMAGE_BYTES)
        return null;
      var source = body.source();
      if (source.request(MAX_IMAGE_BYTES + 1)) return null;
      byte[] bytes = source.readByteArray();
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
      int edge = Math.max(options.outWidth, options.outHeight);
      if (edge <= 0 || edge > 8192) return null;
      options.inSampleSize = 1;
      while (edge / options.inSampleSize > 192) options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
  }
}
