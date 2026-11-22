package kr.jamsuham.fastpack;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 서버 리소스팩을 HTTP Range 로 여러 조각 나눠 동시에 받는다.
 *
 * 바닐라와 똑같은 결과를 만든다: 같은 폴더(saveDir)에 같은 이름(SHA-1 16진수)으로, 같은 해시 검사, 같은 크기 제한.
 * 팩 내용은 한 바이트도 바꾸지 않는다(풀거나 다시 압축하지 않음 → 보호된 팩도 그대로).
 * 조금이라도 이상하면 null 을 돌려주고, 그러면 바닐라가 원래 방식으로 받는다. 예외를 밖으로 던지지 않는다.
 */
public final class ParallelDownload {
    static final Logger LOG = LoggerFactory.getLogger("fastpack");

    /** 이보다 작은 파일은 나눠 받을 이득이 적어 바닐라에 맡긴다 */
    private static final long MIN_SIZE = 8L * 1024 * 1024;
    /** 동시 연결 수 */
    private static final int CONNECTIONS = 6;
    /** 한 번에 요청하는 조각 크기 (빠른 연결이 남은 조각을 더 가져간다) */
    private static final long SEGMENT = 4L * 1024 * 1024;
    /** 조각 하나당 다시 시도 횟수 */
    private static final int RETRIES = 3;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final AtomicInteger THREAD_NO = new AtomicInteger();

    /** 바닐라 진행 표시(HttpUtil.DownloadProgressListener)를 감싼 것. 마인크래프트 클래스에 직접 묶이지 않게 */
    public interface Progress {
        void requestStart();
        void downloadStart(long total);
        void downloadedBytes(long bytes);
        void requestFinished(boolean ok);
    }

    private ParallelDownload() {}

    /** 성공하면 바닐라가 돌려줬을 경로, 아니면 null(바닐라로 넘김) */
    public static Path tryDownload(Path saveDir, URL url, Map<String, String> headers, HashFunction hashFunction,
                                   HashCode requestedHash, int maxSize, Proxy proxy, Progress progress) {
        try {
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!protocol.equals("http") && !protocol.equals("https")) {
                LOG.info("[FastPack] http(s) 주소가 아니라 바닐라로 받습니다: {}", url);
                return null;
            }

            // 이미 받아 둔 파일이 있으면 바닐라가 해시를 확인하고 그대로 쓴다(캐시) → 맡김
            if (requestedHash != null && Files.exists(saveDir.resolve(requestedHash.toString()))) {
                LOG.info("[FastPack] 이미 받아 둔 팩이라 다운로드하지 않습니다 (걸리는 시간은 팩 적용 단계): {}", requestedHash);
                return null;
            }

            Probe probe = probe(url, headers, proxy);
            if (probe == null) return null;
            if (probe.total < MIN_SIZE) {
                LOG.info("[FastPack] 팩이 작아서({} KB) 바닐라로 받습니다", probe.total / 1024);
                return null;
            }
            if (probe.total > maxSize) {
                LOG.info("[FastPack] 팩이 크기 한도({} MB)보다 커서 바닐라에 맡깁니다", maxSize / (1024 * 1024));
                return null; // 바닐라가 똑같은 오류를 내도록 맡김
            }

            return download(saveDir, probe, headers, hashFunction, requestedHash, proxy, progress);
        } catch (Throwable t) {
            LOG.warn("[FastPack] 나눠 받기 실패, 바닐라 방식으로 받습니다: {}", t.toString());
            return null;
        }
    }

    private record Probe(URL url, long total, String validator) {}

    /** bytes=0-0 을 요청해 Range 지원 여부와 전체 크기를 알아낸다 */
    private static Probe probe(URL url, Map<String, String> headers, Proxy proxy) throws IOException {
        HttpURLConnection c = open(url, headers, proxy);
        try {
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code != 206) {
                LOG.info("[FastPack] 서버가 Range 를 지원하지 않아 바닐라로 받습니다 (응답 {})", code);
                return null;
            }
            Matcher m = CONTENT_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
            if (!m.find() || Long.parseLong(m.group(1)) != 0) return null;
            long total = Long.parseLong(m.group(3));
            drain(c);
            String etag = c.getHeaderField("ETag");
            String validator = etag != null && !etag.startsWith("W/") ? etag : c.getHeaderField("Last-Modified");
            // 리다이렉트를 따라간 뒤의 실제 주소로 조각을 받는다
            return new Probe(c.getURL(), total, validator);
        } finally {
            c.disconnect();
        }
    }

    private static Path download(Path saveDir, Probe probe, Map<String, String> headers, HashFunction hashFunction,
                                 HashCode requestedHash, Proxy proxy, Progress progress) throws Exception {
        long total = probe.total;
        int segments = (int) ((total + SEGMENT - 1) / SEGMENT);
        int threads = Math.min(CONNECTIONS, segments);
        LOG.info("[FastPack] {} 를 {} 개 연결로 나눠 받습니다 ({} MB)", probe.url, threads, total / (1024 * 1024));
        final long startedAt = System.nanoTime();

        Files.createDirectories(saveDir);
        Path tmp = Files.createTempFile(saveDir, "fastpack", ".tmp");
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "FastPack-" + THREAD_NO.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            progress.requestStart();
            progress.downloadStart(total);

            AtomicInteger next = new AtomicInteger();
            AtomicLong done = new AtomicLong();
            AtomicBoolean failed = new AtomicBoolean();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    int s;
                    while (!failed.get() && (s = next.getAndIncrement()) < segments) {
                        long from = s * SEGMENT;
                        long to = Math.min(total, from + SEGMENT) - 1;
                        fetchSegment(probe, headers, proxy, ch, from, to, done, failed);
                    }
                    return null;
                }));
            }

            // 진행 표시는 이 스레드(바닐라 다운로드 스레드)에서만 부른다
            for (Future<?> f : futures) {
                while (true) {
                    try {
                        f.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException e) {
                        progress.downloadedBytes(done.get());
                    } catch (java.util.concurrent.ExecutionException e) {
                        failed.set(true);
                        throw new IOException("조각 받기 실패", e.getCause());
                    }
                }
            }
            if (failed.get() || done.get() != total) throw new IOException("받은 크기가 맞지 않음 " + done.get() + "/" + total);
            progress.downloadedBytes(total);
            ch.force(false);

            // 바닐라와 같은 해시 검사
            HashCode actual = hash(ch, hashFunction);
            ch.close();
            if (requestedHash != null && !actual.equals(requestedHash)) {
                throw new IOException("해시 불일치: 받은 것 " + actual + ", 요청 " + requestedHash);
            }
            Path target = saveDir.resolve((requestedHash != null ? requestedHash : actual).toString());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
            } catch (IOException ignored) {
            }
            progress.requestFinished(true);
            LOG.info("[FastPack] 다 받았습니다: {} ({}초)", target.getFileName(), String.format(Locale.ROOT, "%.1f", (System.nanoTime() - startedAt) / 1e9));
            return target;
        } finally {
            // 실패해도 진행 표시에 실패를 알리지 않는다: 바닐라가 requestStart 부터 다시 시작한다
            pool.shutdownNow();
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    private static void fetchSegment(Probe probe, Map<String, String> headers, Proxy proxy, FileChannel ch,
                                     long from, long to, AtomicLong done, AtomicBoolean failed) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES && !failed.get(); attempt++) {
            long written = 0;
            HttpURLConnection c = open(probe.url, headers, proxy);
            try {
                c.setRequestProperty("Range", "bytes=" + from + "-" + to);
                if (probe.validator != null) c.setRequestProperty("If-Range", probe.validator);
                int code = c.getResponseCode();
                if (code != 206) throw new IOException("조각 응답 " + code);
                Matcher m = CONTENT_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                if (!m.find() || Long.parseLong(m.group(1)) != from || Long.parseLong(m.group(2)) != to
                        || Long.parseLong(m.group(3)) != probe.total) {
                    throw new IOException("Content-Range 가 맞지 않음: " + c.getHeaderField("Content-Range"));
                }
                long need = to - from + 1;
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = c.getInputStream()) {
                    while (written < need) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, need - written));
                        if (n < 0) throw new IOException("조각이 중간에 끊김");
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        long pos = from + written;
                        while (bb.hasRemaining()) pos += ch.write(bb, pos);
                        written += n;
                        done.addAndGet(n);
                    }
                }
                return;
            } catch (IOException e) {
                last = e;
                done.addAndGet(-written); // 다시 받을 부분은 진행에서 뺀다
            } finally {
                c.disconnect();
            }
        }
        failed.set(true);
        throw last != null ? last : new IOException("취소됨");
    }

    private static HttpURLConnection open(URL url, Map<String, String> headers, Proxy proxy) throws IOException {
        // 바닐라와 같은 프록시·같은 헤더(User-Agent 등)
        HttpURLConnection c = (HttpURLConnection) url.openConnection(proxy);
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        headers.forEach(c::setRequestProperty);
        // 압축되면 바이트 위치가 달라지므로 원본 그대로 요청
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private static void drain(HttpURLConnection c) {
        try (InputStream in = c.getInputStream()) {
            in.readAllBytes();
        } catch (IOException ignored) {
        }
    }

    private static HashCode hash(FileChannel ch, HashFunction hashFunction) throws IOException {
        Hasher h = hashFunction.newHasher();
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        long pos = 0;
        while (true) {
            buf.clear();
            int n = ch.read(buf, pos);
            if (n < 0) break;
            pos += n;
            buf.flip();
            h.putBytes(buf);
        }
        return h.hash();
    }
}
