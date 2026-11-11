package kr.seoulhousing.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class HousingDetailCollector {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24));
    private final AnnouncementRepository repository;
    private final LhDetailParser parser;
    private final Path documents;
    private final int maxPerRun;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    HousingDetailCollector(AnnouncementRepository repository, LhDetailParser parser,
                           @Value("${housing.root}") String root,
                           @Value("${housing.detail.max-per-run:10}") int maxPerRun) {
        this.repository = repository;
        this.parser = parser;
        this.documents = Path.of(root).resolve("data/documents").normalize();
        this.maxPerRun = Math.max(1, maxPerRun);
    }

    Map<String, Object> collect() {
        LocalDateTime started = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        String at = started.format(TIME);
        List<AnnouncementRepository.DetailCandidate> candidates = repository.detailCandidates(LhDetailParser.VERSION, at, maxPerRun);
        int attempted = 0, parsed = 0, partial = 0, failed = 0, skipped = 0;
        for (AnnouncementRepository.DetailCandidate candidate : candidates) {
            if (attempted > 0) {
                try { Thread.sleep(300); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            attempted++;
            if (isNotRecruitment(candidate.title())) {
                repository.markDetailNotApplicable(candidate.id(), at, LhDetailParser.VERSION);
                skipped++;
                continue;
            }
            try {
                String html = fetch(candidate.officialUrl());
                LhDetailParser.Result result = parser.parse(html);
                String hash = sha(html);
                String localPath = store(candidate.id(), hash, html);
                repository.saveCollectedDetail(candidate.id(), result.status(), result.sites(), at,
                        LhDetailParser.VERSION, hash, candidate.officialUrl(), localPath);
                if ("PARSED".equals(result.status())) parsed++; else partial++;
            } catch (Exception error) {
                failed++;
                String nextRetry = transientFailure(error)
                        ? started.plus(RETRY_DELAYS.get(Math.min(candidate.attemptCount(), RETRY_DELAYS.size() - 1))).format(TIME)
                        : null;
                repository.markDetailFailure(candidate.id(), at, LhDetailParser.VERSION, message(error), nextRetry);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempted", attempted);
        result.put("parsed", parsed);
        result.put("partial", partial);
        result.put("failed", failed);
        result.put("skipped", skipped);
        return result;
    }

    protected String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "SeoulHousingMonitor/1.0")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new HttpStatusException(response.statusCode());
        return response.body();
    }

    private String store(String announcementId, String hash, String html) throws IOException {
        Path directory = documents.resolve(announcementId).normalize();
        if (!directory.startsWith(documents)) throw new IOException("잘못된 공고 문서 경로입니다.");
        Files.createDirectories(directory);
        Path target = directory.resolve(hash + ".html");
        if (!Files.exists(target)) {
            Path temporary = Files.createTempFile(directory, "detail-", ".tmp");
            Files.writeString(temporary, html, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return documents.getParent().relativize(target).toString().replace('\\', '/');
    }

    private boolean isNotRecruitment(String title) {
        String normalized = title.replaceAll("\\s+", "");
        return normalized.contains("당첨자발표") || normalized.contains("당첨결과") || normalized.contains("예비입주자순번");
    }

    private boolean transientFailure(Exception error) {
        if (error instanceof HttpTimeoutException || error instanceof ConnectException || error instanceof IOException) return true;
        return error instanceof HttpStatusException status && (status.code == 429 || status.code >= 500);
    }

    private String message(Exception error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private String sha(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static final class HttpStatusException extends IOException {
        private final int code;
        private HttpStatusException(int code) { super("HTTP " + code); this.code = code; }
    }
}
