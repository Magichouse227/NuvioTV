package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type testClock struct {
	now atomic.Int64
}

func newTestClock(value time.Time) *testClock {
	clock := &testClock{}
	clock.now.Store(value.UnixNano())
	return clock
}

func (c *testClock) Now() time.Time {
	return time.Unix(0, c.now.Load())
}

func (c *testClock) Advance(value time.Duration) {
	c.now.Add(int64(value))
}

func TestParseRetryAfterUsesSafeRemainingValues(t *testing.T) {
	now := time.Date(2026, time.January, 2, 3, 4, 5, int(100*time.Millisecond), time.UTC)
	date := now.Add(1500 * time.Millisecond).UTC().Format(http.TimeFormat)
	tests := []struct {
		name   string
		header string
		want   int64
		known  bool
	}{
		{name: "seconds", header: "7", want: 7, known: true},
		{name: "zero", header: "0", want: 0, known: true},
		{name: "date", header: date, want: 1, known: true},
		{name: "past date", header: now.Add(-time.Second).UTC().Format(http.TimeFormat), want: 0, known: true},
		{name: "malformed", header: "not-a-retry-after", known: false},
		{name: "missing", header: "", known: false},
		{name: "overflow", header: "999999999999999999999999999999999999", want: maxRetryAfterSeconds, known: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			parsed := parseRetryAfter(test.header, now)
			if !test.known {
				if parsed != nil {
					t.Fatalf("parseRetryAfter(%q) = %#v, want nil", test.header, parsed)
				}
				return
			}
			if parsed == nil {
				t.Fatalf("parseRetryAfter(%q) = nil", test.header)
			}
			remaining := parsed.remaining(now)
			if remaining == nil || *remaining != test.want {
				t.Fatalf("remaining = %v, want %d", remaining, test.want)
			}
		})
	}
}

func TestNZBEndpointKeyNormalizesOriginOnly(t *testing.T) {
	first, err := nzbEndpointKey("HTTP://User:secret@EXAMPLE.com:080/path?a=1")
	if err != nil {
		t.Fatal(err)
	}
	second, err := nzbEndpointKey("http://example.com:80/other?b=2")
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("keys differ: %q and %q", first, second)
	}
	https, err := nzbEndpointKey("https://example.com/other")
	if err != nil {
		t.Fatal(err)
	}
	if first == https {
		t.Fatal("HTTP and HTTPS origins share a cooldown")
	}
}

func TestNZBRateLimitKeepsIndependentOriginsSeparate(t *testing.T) {
	clock := newTestClock(time.Date(2026, time.January, 2, 3, 4, 5, 0, time.UTC))
	limiter := newNZBRateLimiter(clock.Now)
	limiter.record("http://one.example:80", nil)
	if limiter.blocked("http://one.example:80") == nil {
		t.Fatal("same origin was not blocked")
	}
	if limiter.blocked("http://two.example:80") != nil {
		t.Fatal("independent origin inherited cooldown")
	}
}

func TestKnownRetryAfterControlsCooldownExpiry(t *testing.T) {
	clock := newTestClock(time.Date(2026, time.January, 2, 3, 4, 5, 0, time.UTC))
	limiter := newNZBRateLimiter(clock.Now)
	long := limiter.record("http://long.example:80", parseRetryAfter("120", clock.Now()))
	if long.cooldownSeconds != 120 {
		t.Fatalf("initial known cooldown = %d, want 120", long.cooldownSeconds)
	}
	clock.Advance(61 * time.Second)
	blocked := limiter.blocked("http://long.example:80")
	if blocked == nil || blocked.cooldownSeconds != 59 {
		t.Fatalf("cooldown after 61 seconds = %+v, want 59-second block", blocked)
	}
	clock.Advance(59 * time.Second)
	if blocked = limiter.blocked("http://long.example:80"); blocked != nil {
		t.Fatalf("120-second cooldown remained after expiry: %+v", blocked)
	}

	short := limiter.record("http://short.example:80", parseRetryAfter("5", clock.Now()))
	if short.cooldownSeconds != 5 {
		t.Fatalf("initial short cooldown = %d, want 5", short.cooldownSeconds)
	}
	clock.Advance(5 * time.Second)
	if blocked = limiter.blocked("http://short.example:80"); blocked != nil {
		t.Fatalf("5-second cooldown remained after expiry: %+v", blocked)
	}

	zero := limiter.record("http://zero.example:80", parseRetryAfter("0", clock.Now()))
	if zero.cooldownSeconds != 0 {
		t.Fatalf("zero Retry-After cooldown = %d, want 0", zero.cooldownSeconds)
	}
	if blocked = limiter.blocked("http://zero.example:80"); blocked != nil {
		t.Fatalf("zero Retry-After left a gate: %+v", blocked)
	}

	unknown := limiter.record("http://unknown.example:80", nil)
	if unknown.retryAfterSeconds != nil || unknown.cooldownSeconds != 60 {
		t.Fatalf("unknown Retry-After response = %+v, want null retry and 60-second gate", unknown)
	}
	clock.Advance(59 * time.Second)
	if blocked = limiter.blocked("http://unknown.example:80"); blocked == nil || blocked.cooldownSeconds != 1 {
		t.Fatalf("unknown cooldown after 59 seconds = %+v, want 1-second block", blocked)
	}
	clock.Advance(time.Second)
	if blocked = limiter.blocked("http://unknown.example:80"); blocked != nil {
		t.Fatalf("unknown cooldown remained after expiry: %+v", blocked)
	}
}

func TestNZBNon429ResponsesAreNotRateLimited(t *testing.T) {
	for _, status := range []int{http.StatusServiceUnavailable, 430} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(status)
			}))
			defer server.Close()
			coordinator := newNZBDownloadCoordinator(newNZBRateLimiter(time.Now))
			_, err := coordinator.download(context.Background(), server.URL, server.Client())
			var rateErr *nzbRateLimitError
			if errors.As(err, &rateErr) {
				t.Fatalf("HTTP %d was classified as rate limited", status)
			}
			if err == nil {
				t.Fatalf("HTTP %d unexpectedly succeeded", status)
			}
		})
	}
}

func TestNZBRateLimitGatesRedirectFinalOrigin(t *testing.T) {
	clock := newTestClock(time.Date(2026, time.January, 2, 3, 4, 5, 0, time.UTC))
	limiter := newNZBRateLimiter(clock.Now)
	coordinator := newNZBDownloadCoordinator(limiter)
	var finalRequests atomic.Int64
	final := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		finalRequests.Add(1)
		writer.Header().Set("Retry-After", "9")
		writer.WriteHeader(http.StatusTooManyRequests)
	}))
	defer final.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		http.Redirect(writer, request, final.URL, http.StatusTemporaryRedirect)
	}))
	defer redirect.Close()
	differentRedirect := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		http.Redirect(writer, request, final.URL, http.StatusTemporaryRedirect)
	}))
	defer differentRedirect.Close()

	_, err := coordinator.download(context.Background(), redirect.URL, redirect.Client())
	var firstRateErr *nzbRateLimitError
	if !errors.As(err, &firstRateErr) {
		t.Fatalf("redirect response error = %v, want rate limit", err)
	}
	_, err = coordinator.download(context.Background(), differentRedirect.URL, differentRedirect.Client())
	var secondRedirectRateErr *nzbRateLimitError
	if !errors.As(err, &secondRedirectRateErr) {
		t.Fatalf("different original-origin error = %v, want local rate limit", err)
	}
	_, err = coordinator.download(context.Background(), final.URL+"/different-path", final.Client())
	var secondRateErr *nzbRateLimitError
	if !errors.As(err, &secondRateErr) {
		t.Fatalf("direct final-origin error = %v, want local rate limit", err)
	}
	if got := finalRequests.Load(); got != 1 {
		t.Fatalf("final-origin requests = %d, want one", got)
	}
}

func TestSessionPOSTReturnsSafeStructuredNZBRateLimit(t *testing.T) {
	clock := newTestClock(time.Date(2026, time.January, 2, 3, 4, 5, 0, time.UTC))
	limiter := newNZBRateLimiter(clock.Now)
	coordinator := newNZBDownloadCoordinator(limiter)
	var upstreamRequests atomic.Int64
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstreamRequests.Add(1)
		writer.Header().Set("Retry-After", "7")
		writer.WriteHeader(http.StatusTooManyRequests)
		_, _ = io.WriteString(writer, "provider secret body")
	}))
	defer upstream.Close()

	registry := newSessionRegistry(2, time.Minute)
	registry.nzbDownloads = coordinator
	defer registry.closeAll()
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	payload := `{"nzbUrl":"` + upstream.URL + `/first?secret=query","servers":["nntp://example.invalid"]}`

	first := postSessionForTest(t, api, payload)
	if first.Code != http.StatusTooManyRequests {
		t.Fatalf("first status = %d, want 429", first.Code)
	}
	var firstBody rateLimitErrorResponse
	if err := json.Unmarshal(first.Body.Bytes(), &firstBody); err != nil {
		t.Fatal(err)
	}
	if firstBody.Code != "nzb_rate_limited" || firstBody.HTTPStatus != http.StatusTooManyRequests {
		t.Fatalf("unexpected rate body: %+v", firstBody)
	}
	if firstBody.RetryAfterSeconds == nil || *firstBody.RetryAfterSeconds != 7 {
		t.Fatalf("retryAfterSeconds = %v, want 7", firstBody.RetryAfterSeconds)
	}
	if firstBody.CooldownSeconds != 7 {
		t.Fatalf("cooldownSeconds = %d, want 7", firstBody.CooldownSeconds)
	}
	if strings.Contains(first.Body.String(), "provider secret") || strings.Contains(first.Body.String(), "secret=query") {
		t.Fatalf("rate response leaked upstream data: %q", first.Body.String())
	}

	second := postSessionForTest(t, api, `{"nzbUrl":"`+upstream.URL+`/second?other=secret","servers":["nntp://example.invalid"]}`)
	if second.Code != http.StatusTooManyRequests {
		t.Fatalf("cooldown status = %d, want 429", second.Code)
	}
	var secondBody rateLimitErrorResponse
	if err := json.Unmarshal(second.Body.Bytes(), &secondBody); err != nil {
		t.Fatal(err)
	}
	if secondBody.RetryAfterSeconds == nil || *secondBody.RetryAfterSeconds != 7 {
		t.Fatalf("cooldown retryAfterSeconds = %v, want 7", secondBody.RetryAfterSeconds)
	}
	if got := upstreamRequests.Load(); got != 1 {
		t.Fatalf("upstream requests during cooldown = %d, want 1", got)
	}

	clock.Advance(nzbLocalCooldown)
	third := postSessionForTest(t, api, payload)
	if third.Code != http.StatusTooManyRequests {
		t.Fatalf("post-expiry status = %d, want 429", third.Code)
	}
	if got := upstreamRequests.Load(); got != 2 {
		t.Fatalf("upstream requests after expiry = %d, want 2", got)
	}
}

func postSessionForTest(t *testing.T, api *apiServer, payload string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/v1/sessions", strings.NewReader(payload))
	request.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)
	return response
}

func TestNZBDownloadDeduplicatesWithIndependentCancellation(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	var upstreamRequests atomic.Int64
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstreamRequests.Add(1)
		close(started)
		select {
		case <-release:
		case <-request.Context().Done():
			return
		}
		_, _ = io.WriteString(writer, validNZB)
	}))
	defer upstream.Close()

	coordinator := newNZBDownloadCoordinator(newNZBRateLimiter(time.Now))
	firstContext, cancelFirst := context.WithCancel(context.Background())
	firstDone := make(chan error, 1)
	go func() {
		_, err := coordinator.download(firstContext, upstream.URL, upstream.Client())
		firstDone <- err
	}()
	<-started

	secondDone := make(chan error, 1)
	go func() {
		_, err := coordinator.download(context.Background(), upstream.URL, upstream.Client())
		secondDone <- err
	}()
	time.Sleep(10 * time.Millisecond)
	cancelFirst()
	if err := <-firstDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("first waiter error = %v, want cancellation", err)
	}
	close(release)
	if err := <-secondDone; err != nil {
		t.Fatalf("second waiter error = %v", err)
	}
	if got := upstreamRequests.Load(); got != 1 {
		t.Fatalf("upstream requests = %d, want one deduplicated request", got)
	}
}

func TestNZBDownloadCancelsWhenAllWaitersCancel(t *testing.T) {
	requestStarted := make(chan struct{})
	requestCanceled := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		close(requestStarted)
		<-request.Context().Done()
		close(requestCanceled)
	}))
	defer upstream.Close()
	coordinator := newNZBDownloadCoordinator(newNZBRateLimiter(time.Now))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := coordinator.download(ctx, upstream.URL, upstream.Client())
		done <- err
	}()
	<-requestStarted
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("waiter error = %v, want cancellation", err)
	}
	select {
	case <-requestCanceled:
	case <-time.After(time.Second):
		t.Fatal("all-cancel did not cancel upstream request")
	}
}
