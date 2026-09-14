package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	nzbLocalCooldown     = time.Minute
	maxRetryAfterSeconds = int64(1<<63 - 1)
)

const nzbRateLimitedMessage = "NZB download is temporarily rate limited"

// nzbRateLimitError is deliberately independent of the URL and response body.
// The API serializes its fields as the stable loopback API error contract.
type nzbRateLimitError struct {
	retryAfterSeconds *int64
	cooldownSeconds   int64
}

func (e *nzbRateLimitError) Error() string {
	return nzbRateLimitedMessage
}

func (e *nzbRateLimitError) retryAfter() *int64 {
	if e == nil || e.retryAfterSeconds == nil {
		return nil
	}
	value := *e.retryAfterSeconds
	return &value
}

type storedRetryAfter struct {
	seconds    *int64
	until      *time.Time
	receivedAt time.Time
}

func (s storedRetryAfter) remaining(now time.Time) *int64 {
	if s.seconds != nil {
		value := *s.seconds
		if value > 0 {
			if elapsedSeconds := elapsedWholeSeconds(now, s.receivedAt); elapsedSeconds > 0 {
				if elapsedSeconds >= value {
					value = 0
				} else {
					value -= elapsedSeconds
				}
			}
		} else {
			value = 0
		}
		return &value
	}
	if s.until == nil {
		return nil
	}
	value := ceilTimeUntil(*s.until, now)
	return &value
}

type nzbEndpointCooldown struct {
	gate   *storedRetryAfter
	server *storedRetryAfter
}

type nzbRateLimiter struct {
	mu        sync.Mutex
	now       func() time.Time
	cooldowns map[string]nzbEndpointCooldown
}

func newNZBRateLimiter(now func() time.Time) *nzbRateLimiter {
	if now == nil {
		now = time.Now
	}
	return &nzbRateLimiter{
		now:       now,
		cooldowns: make(map[string]nzbEndpointCooldown),
	}
}

func (l *nzbRateLimiter) blocked(endpoint string) *nzbRateLimitError {
	if l == nil {
		return nil
	}
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	cooldown, ok := l.cooldowns[endpoint]
	if !ok {
		return nil
	}
	cooldownSeconds := cooldown.remaining(now)
	if cooldownSeconds <= 0 {
		delete(l.cooldowns, endpoint)
		return nil
	}
	var retryAfterSeconds *int64
	if cooldown.server != nil {
		retryAfterSeconds = cooldown.server.remaining(now)
	}
	return &nzbRateLimitError{
		retryAfterSeconds: retryAfterSeconds,
		cooldownSeconds:   cooldownSeconds,
	}
}

func (l *nzbRateLimiter) record(endpoint string, spec *storedRetryAfter) *nzbRateLimitError {
	if l == nil {
		return &nzbRateLimitError{cooldownSeconds: int64(nzbLocalCooldown / time.Second)}
	}
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	gate := spec
	server := spec
	if gate == nil {
		seconds := int64(nzbLocalCooldown / time.Second)
		gate = &storedRetryAfter{seconds: &seconds, receivedAt: now}
		server = nil
	}
	candidateRemainingValue := gate.remaining(now)
	candidateRemaining := int64(0)
	if candidateRemainingValue != nil {
		candidateRemaining = *candidateRemainingValue
	}
	existing, hasExisting := l.cooldowns[endpoint]
	if hasExisting {
		existingRemaining := existing.remaining(now)
		if existingRemaining > candidateRemaining {
			return l.storeAndDescribeLocked(endpoint, existing, now)
		}
	}
	if candidateRemaining <= 0 {
		delete(l.cooldowns, endpoint)
		return &nzbRateLimitError{
			retryAfterSeconds: retryAfterValue(spec, now),
			cooldownSeconds:   0,
		}
	}
	cooldown := nzbEndpointCooldown{gate: gate, server: server}
	return l.storeAndDescribeLocked(endpoint, cooldown, now)
}

func (l *nzbRateLimiter) storeAndDescribeLocked(
	endpoint string,
	cooldown nzbEndpointCooldown,
	now time.Time,
) *nzbRateLimitError {
	l.cooldowns[endpoint] = cooldown
	var retryAfterSeconds *int64
	if cooldown.server != nil {
		retryAfterSeconds = cooldown.server.remaining(now)
	}
	return &nzbRateLimitError{
		retryAfterSeconds: retryAfterSeconds,
		cooldownSeconds:   cooldown.remaining(now),
	}
}

func (c nzbEndpointCooldown) remaining(now time.Time) int64 {
	if c.gate != nil {
		if value := c.gate.remaining(now); value != nil {
			return *value
		}
	}
	return 0
}

func retryAfterValue(spec *storedRetryAfter, now time.Time) *int64 {
	if spec == nil {
		return nil
	}
	return spec.remaining(now)
}

func ceilDuration(value time.Duration) int64 {
	if value <= 0 {
		return 0
	}
	seconds := int64(value / time.Second)
	if value%time.Second != 0 {
		if seconds == maxRetryAfterSeconds {
			return maxRetryAfterSeconds
		}
		seconds++
	}
	return seconds
}

func ceilTimeUntil(until, now time.Time) int64 {
	if !until.After(now) {
		return 0
	}
	seconds := saturatingSubInt64(until.Unix(), now.Unix())
	nanoseconds := int64(until.Nanosecond()) - int64(now.Nanosecond())
	if nanoseconds < 0 {
		seconds--
		nanoseconds += int64(time.Second)
	}
	if nanoseconds > 0 {
		if seconds == maxRetryAfterSeconds {
			return maxRetryAfterSeconds
		}
		seconds++
	}
	if seconds <= 0 {
		return 0
	}
	return seconds
}

func elapsedWholeSeconds(now, since time.Time) int64 {
	if !now.After(since) {
		return 0
	}
	seconds := saturatingSubInt64(now.Unix(), since.Unix())
	nanoseconds := int64(now.Nanosecond()) - int64(since.Nanosecond())
	if nanoseconds < 0 {
		if seconds > 0 {
			seconds--
		}
	}
	if seconds < 0 {
		return 0
	}
	return seconds
}

func saturatingSubInt64(left, right int64) int64 {
	if right < 0 && left > maxRetryAfterSeconds+right {
		return maxRetryAfterSeconds
	}
	minInt64 := -maxRetryAfterSeconds - 1
	if right > 0 && left < minInt64+right {
		return minInt64
	}
	return left - right
}

func parseRetryAfter(value string, now time.Time) *storedRetryAfter {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	allDigits := true
	for _, character := range value {
		if character < '0' || character > '9' {
			allDigits = false
			break
		}
	}
	if allDigits {
		seconds := maxRetryAfterSeconds
		if parsed, err := strconv.ParseUint(value, 10, 64); err == nil && parsed <= uint64(maxRetryAfterSeconds) {
			seconds = int64(parsed)
		}
		return &storedRetryAfter{
			seconds:    &seconds,
			receivedAt: now,
		}
	}
	if parsed, err := http.ParseTime(value); err == nil {
		return &storedRetryAfter{until: &parsed}
	}
	return nil
}

// nzbEndpointKey intentionally excludes credentials, path, query, and
// fragment. An explicit default port and a missing default port therefore
// share a gate, while different origins do not.
func nzbEndpointKey(rawURL string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || parsed.Opaque != "" {
		return "", fmt.Errorf("invalid NZB URL")
	}
	scheme := strings.ToLower(parsed.Scheme)
	if scheme != "http" && scheme != "https" {
		return "", fmt.Errorf("invalid NZB URL")
	}
	host := strings.ToLower(parsed.Hostname())
	host = strings.TrimSuffix(host, ".")
	if host == "" {
		return "", fmt.Errorf("invalid NZB URL")
	}
	port := parsed.Port()
	if port == "" {
		if scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	} else {
		portNumber, portErr := strconv.Atoi(port)
		if portErr != nil || portNumber < 1 || portNumber > 65535 {
			return "", fmt.Errorf("invalid NZB URL")
		}
		port = strconv.Itoa(portNumber)
	}
	return scheme + "://" + net.JoinHostPort(host, port), nil
}

type nzbFetchResult struct {
	body           []byte
	headerDuration time.Duration
}

type nzbDownloadFlight struct {
	done     chan struct{}
	cancel   context.CancelFunc
	waiters  int
	result   nzbFetchResult
	err      error
	finished bool
	started  bool
}

// nzbDownloadCoordinator shares only immutable response bytes. Every waiter
// parses its own NZB document, so parser/session state is never shared.
type nzbDownloadCoordinator struct {
	mu         sync.Mutex
	inFlight   map[string]*nzbDownloadFlight
	rateLimits *nzbRateLimiter
}

func newNZBDownloadCoordinator(rateLimits *nzbRateLimiter) *nzbDownloadCoordinator {
	if rateLimits == nil {
		rateLimits = newNZBRateLimiter(time.Now)
	}
	return &nzbDownloadCoordinator{
		inFlight:   make(map[string]*nzbDownloadFlight),
		rateLimits: rateLimits,
	}
}

func (c *nzbDownloadCoordinator) download(ctx context.Context, rawURL string, client *http.Client) (nzbFetchResult, error) {
	if err := ctx.Err(); err != nil {
		return nzbFetchResult{}, err
	}
	endpoint, err := nzbEndpointKey(rawURL)
	if err != nil {
		return nzbFetchResult{}, err
	}
	if rateErr := c.rateLimits.blocked(endpoint); rateErr != nil {
		return nzbFetchResult{}, rateErr
	}

	key := nzbFlightKey(rawURL)
	c.mu.Lock()
	flight := c.inFlight[key]
	if flight == nil {
		requestContext, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		flight = &nzbDownloadFlight{
			done:    make(chan struct{}),
			cancel:  cancel,
			waiters: 1,
		}
		c.inFlight[key] = flight
		c.mu.Unlock()
		if rateErr := c.rateLimits.blocked(endpoint); rateErr != nil {
			c.rejectReservedFlight(key, flight, rateErr)
		} else {
			c.mu.Lock()
			canStart := !flight.finished && c.inFlight[key] == flight && flight.waiters > 0
			if canStart {
				flight.started = true
				go c.run(flight, key, endpoint, requestContext, client)
			}
			c.mu.Unlock()
			if !canStart {
				flight.cancel()
			}
		}
	} else {
		flight.waiters++
		c.mu.Unlock()
	}

	select {
	case <-flight.done:
		return flight.result, flight.err
	case <-ctx.Done():
		c.removeWaiter(key, flight)
		return nzbFetchResult{}, ctx.Err()
	}
}

func nzbFlightKey(rawURL string) string {
	trimmed := strings.TrimSpace(rawURL)
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return trimmed
	}
	parsed.Fragment = ""
	parsed.RawFragment = ""
	return parsed.String()
}

func (c *nzbDownloadCoordinator) removeWaiter(key string, flight *nzbDownloadFlight) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if flight.finished {
		return
	}
	if flight.waiters > 0 {
		flight.waiters--
	}
	if flight.waiters == 0 {
		if c.inFlight[key] == flight {
			delete(c.inFlight, key)
		}
		flight.cancel()
		if !flight.started {
			flight.finished = true
			close(flight.done)
		}
	}
}

func (c *nzbDownloadCoordinator) rejectReservedFlight(
	key string,
	flight *nzbDownloadFlight,
	err error,
) {
	c.mu.Lock()
	if flight.finished {
		c.mu.Unlock()
		return
	}
	flight.err = err
	flight.finished = true
	if c.inFlight[key] == flight {
		delete(c.inFlight, key)
	}
	close(flight.done)
	c.mu.Unlock()
	flight.cancel()
}

func (c *nzbDownloadCoordinator) run(
	flight *nzbDownloadFlight,
	key string,
	endpoint string,
	ctx context.Context,
	client *http.Client,
) {
	result, err := fetchNZBBytes(ctx, key, client, c.rateLimits)
	if rateResponse, ok := err.(*nzbHTTP429); ok {
		stored := parseRetryAfter(rateResponse.retryAfterHeader, c.rateLimits.now())
		err = c.rateLimits.record(endpoint, stored)
		if rateResponse.finalEndpoint != "" && rateResponse.finalEndpoint != endpoint {
			// A redirecting candidate is still a request against its
			// original origin, but the final origin must also be gated:
			// otherwise another candidate that names the final URL directly
			// could immediately recontact the provider that just returned 429.
			c.rateLimits.record(rateResponse.finalEndpoint, stored)
		}
	}

	c.mu.Lock()
	flight.result = result
	flight.err = err
	flight.finished = true
	if c.inFlight[key] == flight {
		delete(c.inFlight, key)
	}
	close(flight.done)
	c.mu.Unlock()
	flight.cancel()
}

type nzbHTTP429 struct {
	retryAfterHeader string
	finalEndpoint    string
}

func (e *nzbHTTP429) Error() string {
	return nzbRateLimitedMessage
}

func fetchNZBBytes(
	ctx context.Context,
	rawURL string,
	client *http.Client,
	rateLimits *nzbRateLimiter,
) (nzbFetchResult, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nzbFetchResult{}, fmt.Errorf("invalid NZB URL")
	}
	requestClient := *client
	originalCheckRedirect := requestClient.CheckRedirect
	requestClient.CheckRedirect = func(next *http.Request, via []*http.Request) error {
		if originalCheckRedirect != nil {
			if err := originalCheckRedirect(next, via); err != nil {
				return err
			}
		} else if len(via) >= 10 {
			return errors.New("stopped after 10 redirects")
		}
		if rateLimits != nil && next.URL != nil {
			if endpoint, endpointErr := nzbEndpointKey(next.URL.String()); endpointErr == nil {
				if rateErr := rateLimits.blocked(endpoint); rateErr != nil {
					return rateErr
				}
			}
		}
		return nil
	}
	requestStarted := time.Now()
	response, err := requestClient.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return nzbFetchResult{}, ctx.Err()
		}
		var rateErr *nzbRateLimitError
		if errors.As(err, &rateErr) {
			return nzbFetchResult{}, rateErr
		}
		return nzbFetchResult{}, fmt.Errorf("failed to download NZB")
	}
	defer response.Body.Close()
	headerDuration := time.Since(requestStarted)
	if response.StatusCode == http.StatusTooManyRequests {
		finalEndpoint := ""
		if response.Request != nil && response.Request.URL != nil {
			if endpoint, endpointErr := nzbEndpointKey(response.Request.URL.String()); endpointErr == nil {
				finalEndpoint = endpoint
			}
		}
		return nzbFetchResult{headerDuration: headerDuration}, &nzbHTTP429{
			retryAfterHeader: response.Header.Get("Retry-After"),
			finalEndpoint:    finalEndpoint,
		}
	}
	if response.StatusCode != http.StatusOK {
		return nzbFetchResult{}, fmt.Errorf("failed to download NZB (HTTP %d)", response.StatusCode)
	}
	if response.ContentLength == 0 {
		return nzbFetchResult{}, fmt.Errorf("downloaded NZB is empty")
	}
	if response.ContentLength > maxNZBSize {
		return nzbFetchResult{}, fmt.Errorf("downloaded NZB exceeds the 64 MiB limit")
	}
	limited := io.LimitReader(response.Body, maxNZBSize+1)
	body, err := io.ReadAll(limited)
	if err != nil {
		if ctx.Err() != nil {
			return nzbFetchResult{}, ctx.Err()
		}
		return nzbFetchResult{}, fmt.Errorf("failed to download NZB")
	}
	if len(body) == 0 {
		return nzbFetchResult{}, fmt.Errorf("downloaded NZB is empty")
	}
	if len(body) > maxNZBSize {
		return nzbFetchResult{}, fmt.Errorf("downloaded NZB exceeds the 64 MiB limit")
	}
	return nzbFetchResult{body: body, headerDuration: headerDuration}, nil
}
