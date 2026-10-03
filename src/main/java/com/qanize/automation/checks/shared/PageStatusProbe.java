package com.qanize.automation.checks.shared;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalInt;

/**
 * The status one address answers with, when the browser cannot say.
 *
 * <p>Selenium reports what a document looks like, never what the server said about it. That is why
 * {@code /basic_auth} was reported as a page that did not load: the browser was handed a 401,
 * dismissed the credential prompt, and rendered an empty document with no title — which looks
 * exactly like a broken page and is nothing of the sort. The status is the missing fact, and this
 * is the narrowest way to obtain it.
 *
 * <p><strong>Asked once, and only when the answer changes the verdict.</strong> The page-health
 * check consults this solely when a document came back without a usable title, which is the one
 * case it would otherwise report as a failure it cannot substantiate. A page that produced a title
 * is never probed, so an ordinary run makes no additional request at all.
 *
 * <p><strong>Anonymous, therefore optional.</strong> An anonymous request cannot answer for a page
 * the browser saw while signed in — the server would answer about a visitor the customer is not —
 * so a run that carries a session supplies {@link #NONE} instead and keeps the behaviour it had.
 * That decision belongs to the caller that knows the session; nothing here inspects one.
 */
public interface PageStatusProbe {

    /** No probe. The answer is always absent and the caller keeps its existing verdict. */
    PageStatusProbe NONE = url -> OptionalInt.empty();

    /**
     * The status this address answers with, or empty when it could not be established.
     *
     * <p>Never throws: an address that cannot be requested is simply an absent answer, because a
     * page-health verdict must not depend on whether a supplementary request succeeded.
     */
    OptionalInt statusOf(String url);

    /**
     * A probe that requests the address as an anonymous visitor.
     *
     * <p>Redirects are followed, exactly as a browser follows them, so the status is the one the
     * address finally answered with rather than the first hop's 302.
     */
    static PageStatusProbe anonymous(Duration timeout) {
        Duration requestTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
            ? Duration.ofSeconds(10)
            : timeout;
        return url -> {
            if (url == null || url.isBlank()) return OptionalInt.empty();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestTimeout)
                    .GET()
                    .build();
                HttpResponse<Void> response =
                    Client.SHARED.send(request, HttpResponse.BodyHandlers.discarding());
                return OptionalInt.of(response.statusCode());
            } catch (InterruptedException interrupted) {
                // The flag is restored rather than swallowed: whoever interrupted this run is
                // entitled to have that seen by everything above.
                Thread.currentThread().interrupt();
                return OptionalInt.empty();
            } catch (Exception unavailable) {
                // Deliberately silent. This is a supplementary question, and failing to answer it
                // leaves the caller exactly where it was rather than inventing a status.
                return OptionalInt.empty();
            }
        };
    }

    /** Configured once and shared, mirroring the anonymous client the Links check already uses. */
    final class Client {

        static final HttpClient SHARED = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        private Client() {
        }
    }
}
