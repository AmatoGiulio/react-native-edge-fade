import { useCallback, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import { EdgeFadeView } from 'react-native-edge-fade';

const MIN_WEBVIEW_HEIGHT = 1600;
const VIDEO_URI =
  'data:video/mp4;base64,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';

function buildHtml(videoUri: string) {
  return `<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
  <style>
    html, body {
      margin: 0;
      padding: 0;
      background: #000;
      color: #f7f0e8;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    body { overflow: hidden; }
    .hero {
      height: 308px;
      background:
        linear-gradient(180deg, rgba(255,60,0,0.9), rgba(138,30,0,0.98)),
        radial-gradient(circle at 24% 12%, rgba(255,255,255,0.28), transparent 30%);
    }
    .quote {
      min-height: 232px;
      display: grid;
      place-items: center;
      background: #670019;
      border-top: 1px solid rgba(0,0,0,0.45);
      border-bottom: 1px solid rgba(255,255,255,0.08);
    }
    .quote p {
      width: min(56vw, 260px);
      font-family: "Comic Sans MS", "Marker Felt", cursive;
      font-size: 22px;
      line-height: 1.15;
      margin: 0;
      text-align: left;
    }
    .article {
      background: #9dced4;
      color: #061116;
      padding: 120px 28px 56px;
      font-family: Georgia, "Times New Roman", serif;
    }
    .article p {
      font-size: 26px;
      line-height: 1.07;
      margin: 0 0 26px;
      max-width: 330px;
    }
    .article em { text-decoration: underline; }
    .videoBlock {
      margin: 36px -28px 34px;
      background: #111;
      border-top: 1px solid rgba(255,255,255,0.14);
      border-bottom: 1px solid rgba(255,255,255,0.14);
    }
    video {
      display: block;
      width: 100%;
      height: 245px;
      object-fit: cover;
      background:
        linear-gradient(135deg, rgba(255,255,255,0.16), transparent),
        #200;
    }
    img {
      display: block;
      width: calc(100% + 56px);
      height: 260px;
      margin: 38px -28px;
      object-fit: cover;
    }
    .footer {
      height: 520px;
      background: linear-gradient(180deg, #111, #3c000d);
    }
  </style>
</head>
<body>
  <section class="hero"></section>
  <section class="quote">
    <p>Maybe that's<br />why Comic Sans<br />has endured.</p>
  </section>
  <section class="article">
    <p><em>In The Book of Circles: Visualizing Spheres of Knowledge</em>, data designer Manuel Lima writes that this fondness for circles is generally viewed as evolutionary.</p>
    <p>The upturned arching of mouths, the circular iris, the shape of the sun and moon: all are simple forms with a strong emotional charge.</p>
    <div class="videoBlock">
      <video
        src="${videoUri}"
        muted
        loop
        autoplay
        controls
        preload="auto"
        playsinline
        webkit-playsinline
      ></video>
    </div>
    <p>Inline media and dynamic layout make this a closer match for a WKWebView content layer than the image-only demo.</p>
    <img src="https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=900&q=80" />
    <p>The fade above should stay clean while the WebView scrolls under the native blur and frost veil.</p>
  </section>
  <section class="footer"></section>
  <script>
    const video = document.querySelector('video');
    video.addEventListener('error', function () {
      const code = video.error ? video.error.code : 'unknown';
      window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'video-error', code, src: video.currentSrc || video.src }));
    });
    video.addEventListener('loadeddata', function () {
      window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'video-loaded', src: video.currentSrc || video.src }));
    });
    video.addEventListener('canplay', function () {
      video.play().catch(function () {});
    });
    function postHeight() {
      window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'height', value: document.documentElement.scrollHeight }));
    }
    window.addEventListener('load', postHeight);
    window.addEventListener('resize', postHeight);
    setTimeout(postHeight, 250);
    setTimeout(postHeight, 1000);
  </script>
</body>
</html>`;
}

export function WebViewIssueScreen() {
  const html = useMemo(() => buildHtml(VIDEO_URI), []);
  const [height, setHeight] = useState(MIN_WEBVIEW_HEIGHT);

  const onMessage = useCallback((event: WebViewMessageEvent) => {
    let next = Number(event.nativeEvent.data);
    try {
      const message = JSON.parse(event.nativeEvent.data) as {
        type?: string;
        value?: number;
      };
      if (message.type === 'height') next = Number(message.value);
    } catch {
      // Older messages were plain numbers; keep supporting them.
    }
    if (Number.isFinite(next)) {
      setHeight(Math.max(MIN_WEBVIEW_HEIGHT, Math.ceil(next)));
    }
  }, []);

  return (
    <EdgeFadeView
      mode="blur"
      top={118}
      blurRadius={12}
      curve="soft"
      style={s.root}
    >
      <View style={s.black}>
        <ScrollView
          bounces
          scrollEventThrottle={16}
          showsVerticalScrollIndicator={false}
        >
          <WebView
            key={html}
            source={{ html, baseUrl: 'https://www.w3schools.com' }}
            style={[s.webview, { height }]}
            originWhitelist={['*']}
            javaScriptEnabled
            domStorageEnabled
            scrollEnabled={false}
            allowsInlineMediaPlayback
            allowsAirPlayForMediaPlayback
            allowsFullscreenVideo
            mediaPlaybackRequiresUserAction={false}
            onMessage={onMessage}
          />
        </ScrollView>
      </View>
    </EdgeFadeView>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },
  black: { flex: 1, backgroundColor: '#000' },
  webview: {
    backgroundColor: '#000',
  },
});
